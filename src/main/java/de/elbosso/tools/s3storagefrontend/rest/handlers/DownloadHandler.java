package de.elbosso.tools.s3storagefrontend.rest.handlers;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.micrometer.core.instrument.Metrics;
import org.jetbrains.annotations.NotNull;

public class DownloadHandler extends java.lang.Object implements Handler
{
	final static java.lang.String RESOURCENAME="download";
	private final static org.slf4j.Logger CLASS_LOGGER=org.slf4j.LoggerFactory.getLogger(DownloadHandler.class);
	private final static org.slf4j.Logger EXCEPTION_LOGGER=org.slf4j.LoggerFactory.getLogger("ExceptionCatcher");

	public static void register(Javalin app)
	{
		app.get("/"+RESOURCENAME+"/:uuid", new DownloadHandler());
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for download: /"+RESOURCENAME+"/<id> (allowed methods: GET)");
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
					ctx.status(201);
					ctx.contentType(objectPortion.getObjectMetadata().getContentType());
					ctx.header("Content-Disposition", "filename=\"" + objectPortion.getObjectMetadata().getContentDisposition() + "\"");
					if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("Content-Disposition "+objectPortion.getObjectMetadata().getContentDisposition());
					ctx.result(new java.io.ByteArrayInputStream(content));
					Metrics.counter("s3storagefrontend.get", "resourcename", "/"+RESOURCENAME, "remoteAddr", ctx.req.getRemoteAddr(), "remoteHost", ctx.req.getRemoteHost(), "localAddr", ctx.req.getLocalAddr(), "localName", ctx.req.getLocalName()).increment();
				}
				catch(com.amazonaws.services.s3.model.AmazonS3Exception axp)
				{
					ctx.status(axp.getStatusCode());
					ctx.result(axp.getMessage());
					if(CLASS_LOGGER.isErrorEnabled())CLASS_LOGGER.error(axp.getMessage());
					Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus",java.lang.Integer.toString(axp.getStatusCode()),"error",axp.getMessage(),"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
				}
			}
			else
			{
				ctx.status(404);
				ctx.result("The specified key does not exist ("+fileObjKeyName+")!");
				if(CLASS_LOGGER.isErrorEnabled())CLASS_LOGGER.error("The specified key does not exist ("+fileObjKeyName+")!");
				Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus","404","error","object key does not exist","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
		}
		else
		{
			ctx.status(500);
			ctx.result("No key specified!");
			if(CLASS_LOGGER.isErrorEnabled())CLASS_LOGGER.error("No key specified!");
			Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus","500","error","uuid not set","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
		}
	}
}
