package de.elbosso.tools.s3storagefrontend.rest.handlers;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.micrometer.core.instrument.Metrics;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Priority;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

public class Rfc3161Handler extends Object implements Handler
{
	static final java.lang.String RFC3161URLENVKEY="de.elbosso.tools.s3storagefrontend.rest.App.rfc3161url";
	private static final java.lang.String RFC3161POLICYOIDENVKEY="de.elbosso.tools.s3storagefrontend.rest.App.tspolicyoid";
	private static final java.lang.String RFC3161CERTREQENVKEY="de.elbosso.tools.s3storagefrontend.rest.App.tscertreq";
	private static final java.lang.String RFC3161BINARYBODYNAMEENVKEY="de.elbosso.tools.s3storagefrontend.rest.App.binarybodyname";
	private static final java.lang.String BASELINETSPOLICYOID="0.4.0.2023.1.1";
	final static String RESOURCENAME="timestamp";
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(Rfc3161Handler.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");
	//for example: http://rfc3161timestampingserver.pi-docker.lab/
	private java.lang.String rfc3161url;

	public static void register(Javalin app)
	{
		if(System.getenv(RFC3161URLENVKEY)!=null)
		{
			app.get("/" + RESOURCENAME + "/:uuid", new Rfc3161Handler(System.getenv(RFC3161URLENVKEY)));
			if (CLASS_LOGGER.isDebugEnabled())
				CLASS_LOGGER.debug("added path for gettint RFC3161 Timestamps: /" + RESOURCENAME + " (allowed methods: GET)");
		}
	}
	private Rfc3161Handler(java.lang.String rfc3161url)
	{
		super();
		this.rfc3161url=rfc3161url;
	}
	@Override
	public void handle(@NotNull Context ctx) throws Exception
	{
		String fileObjKeyName=ctx.pathParam("uuid");
		if(fileObjKeyName!=null)
		{
			if (CLASS_LOGGER.isDebugEnabled()) CLASS_LOGGER.debug("found uuid to be " + fileObjKeyName);

			Regions clientRegion = Regions.DEFAULT_REGION;
			AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
	//							.withRegion(clientRegion)
					.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(System.getenv("AWS_S3_URL"), clientRegion.name()))
					.withPathStyleAccessEnabled(true)
	//							.withClientConfiguration(clientConfiguration)
					.build();
			String bucketName = System.getenv("AWS_BUCKET_NAME");

	// Download file
			GetObjectRequest rangeObjectRequest = new GetObjectRequest(bucketName, fileObjKeyName);
			if(s3Client.doesObjectExist(bucketName, fileObjKeyName))
			{
				try
				{
					S3Object objectPortion = s3Client.getObject(rangeObjectRequest);

					java.io.InputStream is = objectPortion.getObjectContent();
					java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
					de.elbosso.util.Utilities.copyBetweenStreams(is, baos, true);
					byte[] content = baos.toByteArray();
					org.bouncycastle.tsp.TimeStampRequestGenerator generator = new org.bouncycastle.tsp.TimeStampRequestGenerator();
					if(System.getenv(RFC3161POLICYOIDENVKEY)==null)
					{
						generator.setReqPolicy(new ASN1ObjectIdentifier(BASELINETSPOLICYOID));
						if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("no policy defined by "+RFC3161POLICYOIDENVKEY+" using the default value "+BASELINETSPOLICYOID+"!");
					}
					else
					{
						generator.setReqPolicy(new ASN1ObjectIdentifier(System.getenv(RFC3161POLICYOIDENVKEY)));
					}
					generator.setCertReq(System.getenv(RFC3161CERTREQENVKEY)!=null?System.getenv(RFC3161CERTREQENVKEY).equalsIgnoreCase("TRUE"):true);
					java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(content);
					baos = new java.io.ByteArrayOutputStream();
					de.elbosso.util.Utilities.copyBetweenStreams(bais, baos, true);
					MessageDigest digest = MessageDigest.getInstance(TSPAlgorithms.SHA512.toString());
					byte[] in = baos.toByteArray();
					byte[] out = digest.digest(in);
					org.bouncycastle.tsp.TimeStampRequest request = generator.generate(TSPAlgorithms.SHA512, out, new BigInteger(64,
							SecureRandom.getInstance("NativePRNG")));
					byte[] timestampQuery = request.getEncoded();

					java.lang.String s3ContentDisposition=objectPortion.getObjectMetadata().getContentDisposition();
					String cd=s3ContentDisposition.substring(0,s3ContentDisposition.lastIndexOf("."))+".tsq";

					CloseableHttpClient client = HttpClientBuilder.create().build();
					HttpPost post = new HttpPost(rfc3161url);
//
					MultipartEntityBuilder builder = MultipartEntityBuilder.create();
					builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
					builder.addBinaryBody(System.getenv(RFC3161BINARYBODYNAMEENVKEY)!=null?System.getenv(RFC3161BINARYBODYNAMEENVKEY):"tsq", timestampQuery, ContentType.DEFAULT_BINARY, cd);
//
					HttpEntity entity = builder.build();
					post.setEntity(entity);
					CloseableHttpResponse response = client.execute(post);

					int status = response.getStatusLine().getStatusCode();
					if (status >= 200 && status < 300) {
						HttpEntity responseEntity = response.getEntity();
						byte[] timestampReply=EntityUtils.toByteArray(responseEntity);
						s3ContentDisposition=objectPortion.getObjectMetadata().getContentDisposition();
						cd=s3ContentDisposition.substring(0,s3ContentDisposition.lastIndexOf("."))+".tsr";
						ctx.status(201);
						ctx.contentType("application/timestamp-reply");
						ctx.header("Content-Disposition", "filename=\"" + cd + "\"");
						if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("Content-Disposition "+cd);
						ctx.result(new java.io.ByteArrayInputStream(timestampReply));
						Metrics.counter("s3storagefrontend.get", "resourcename", "/"+RESOURCENAME, "remoteAddr", ctx.req.getRemoteAddr(), "remoteHost", ctx.req.getRemoteHost(), "localAddr", ctx.req.getLocalAddr(), "localName", ctx.req.getLocalName()).increment();
					} else {
						ctx.status(status);
						ctx.result(response.getStatusLine().getReasonPhrase());
						if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error(response.getStatusLine().toString());
						Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus",java.lang.Integer.toString(status),"error",response.getStatusLine().toString(),"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
					}
					response.close();
					client.close();
				}
				catch(com.amazonaws.services.s3.model.AmazonS3Exception axp)
				{
					ctx.status(axp.getStatusCode());
					ctx.result(axp.getMessage());
					if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error(axp.getMessage());
					Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus",java.lang.Integer.toString(axp.getStatusCode()),"error",axp.getMessage(),"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
				}
			}
			else
			{
				ctx.status(404);
				ctx.result("The specified key does not exist ("+fileObjKeyName+")!");
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("The specified key does not exist ("+fileObjKeyName+")!");
				Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus","404","error","object key does not exist","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
		}
	}
}
