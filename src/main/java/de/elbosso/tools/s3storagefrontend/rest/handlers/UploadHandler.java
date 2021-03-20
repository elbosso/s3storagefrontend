package de.elbosso.tools.s3storagefrontend.rest.handlers;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.micrometer.core.instrument.Metrics;
import org.apache.log4j.Priority;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.UUID;

public class UploadHandler extends Object implements Handler
{
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(UploadHandler.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");

	@Override
	public void handle(@NotNull Context ctx) throws Exception
	{
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("received upload request");
		byte[] data=null;
		java.lang.String accepts=ctx.header("Accept");
		if(accepts!=null)
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("request specified wanted return as "+accepts);
		java.lang.String contentType=ctx.contentType();
		String fileObjKeyName = UUID.randomUUID().toString();
		java.lang.String s3ContentDisposition=fileObjKeyName;
		java.lang.String s3ContentType="application/octet-stream";
		if(ctx.contentType().startsWith("multipart/form-data"))
		{
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("request is multipart/form-data - searching for data under key\"data\"");
			//curl -F "data=@somefile.suffix" http://localhost:7000/upload
			if(ctx.uploadedFile("data")!=null)
			{
				if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("found it - data length is "+ctx.uploadedFile("data").getContentLength());
				s3ContentDisposition=ctx.uploadedFile("data").getFilename();
				s3ContentType=ctx.uploadedFile("data").getContentType();
				java.io.InputStream is=ctx.uploadedFile("data").getContent();
				java.io.ByteArrayOutputStream baos=new java.io.ByteArrayOutputStream();
				de.elbosso.util.Utilities.copyBetweenStreams(is,baos,true);
				data=baos.toByteArray();
			}
			else
			{
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("no field named \"data\" found in form data . corrupted request?");
				Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus","500","error","data not found","contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			if(ctx.formParam("s3ContentDisposition")!=null)
			{
				s3ContentDisposition=ctx.formParam("s3ContentDisposition");
				if (CLASS_LOGGER.isDebugEnabled()) CLASS_LOGGER.debug("found s3ContentDisposition to be " + s3ContentDisposition);
			}
			else
			{
				if (CLASS_LOGGER.isEnabledFor(Priority.WARN)) CLASS_LOGGER.warn("did not find s3ContentDisposition");
			}
			if(ctx.formParam("s3ContentType")!=null)
			{
				s3ContentType = ctx.formParam("s3ContentType");
				if (CLASS_LOGGER.isDebugEnabled()) CLASS_LOGGER.debug("found s3ContentType to be " + s3ContentType);
			}
			else
			{
				if (CLASS_LOGGER.isEnabledFor(Priority.WARN)) CLASS_LOGGER.warn("did not find s3ContentType");
			}
		}
		else
		{
			if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("request is not multipart/form-data ");
			Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus","500","error","encoding","contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
		}
		if(data!=null)
		{
			try
			{
				// Create a rule to delete objects after 1 days.
				// The rule applies to all objects with the tag "archive" set to "true".
				BucketLifecycleConfiguration.Rule rule2 = new BucketLifecycleConfiguration.Rule()
						.withId("delete rule")
						.withExpirationInDays(1)
						.withStatus(BucketLifecycleConfiguration.ENABLED);

				// Add the rules to a new BucketLifecycleConfiguration.
				BucketLifecycleConfiguration configuration = new BucketLifecycleConfiguration()
						.withRules(Arrays.asList(rule2));

				Regions clientRegion = Regions.DEFAULT_REGION;
				AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
//							.withRegion(clientRegion)
						.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(System.getenv("AWS_S3_URL"), clientRegion.name()))
						.withPathStyleAccessEnabled(true)
//							.withClientConfiguration(clientConfiguration)
						.build();
				String bucketName = System.getenv("AWS_BUCKET_NAME");
				// Save the configuration.
				s3Client.setBucketLifecycleConfiguration(bucketName, configuration);

				java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
				// Upload a file as a new object with ContentType and title specified.
				ObjectMetadata metadata = new ObjectMetadata();
				metadata.setContentDisposition(s3ContentDisposition);
				metadata.setContentType(s3ContentType);
				metadata.setContentLength(data.length);

				//					metadata.addUserMetadata("title", "someTitle");
				PutObjectRequest request = new PutObjectRequest(bucketName, fileObjKeyName, bais,metadata);
				//https://docs.aws.amazon.com/AmazonS3/latest/userguide/tagging-managing.html
				java.util.List<Tag> tags = new java.util.ArrayList<Tag>();
				tags.add(new Tag("archive", "true"));
				request.setTagging(new ObjectTagging(tags));
				s3Client.putObject(request);
				ctx.status(201);
				//ctx.header("Content-Disposition","filename=\"reply.tsr\"");
				//ctx.result(new java.io.ByteArrayInputStream(tsr));
				java.lang.String href=ctx.fullUrl().substring(0,ctx.fullUrl().length()-"upload".length())+"download/"+fileObjKeyName;
				ctx.header("Content-Location",href);
				if(accepts.equals("text/plain"))
				{
					ctx.contentType("text/plain");
					ctx.result(href);
				}
				else
				{
					ctx.contentType("text/html");
					ctx.result("<html><head>" +
							"<link href=\"" + href + "\" rel=\"item\" type=\"" + metadata.getContentType() + "\" />" +
							"</head><body>" +
							"<a href=\"" + href + "\">Download/Share</a>" +
							"</body></html>");
				}
				Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"success","true","contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			catch (AmazonServiceException ase)
			{
				ctx.status(500);
				ctx.result(ase.getMessage());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Caught an AmazonServiceException, which " + "means your request made it "
						+ "to Amazon S3, but was rejected with an error response" + " for some reason.");
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Error Message:    " + ase.getMessage());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("HTTP Status Code: " + ase.getStatusCode());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("AWS Error Code:   " + ase.getErrorCode());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Error Type:       " + ase.getErrorType());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Request ID:       " + ase.getRequestId());
				if(EXCEPTION_LOGGER.isEnabledFor(Priority.ERROR))EXCEPTION_LOGGER.error(ase.getMessage(),ase);
				Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"error",(ase.getMessage()!=null?ase.getMessage():"NPE"),"contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			catch (AmazonClientException ace)
			{
				ctx.status(500);
				ctx.result(ace.getMessage());
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Caught an AmazonClientException, which " + "means the client encountered " + "an internal error while trying to "
						+ "communicate with S3, " + "such as not being able to access the network.");
				if(EXCEPTION_LOGGER.isEnabledFor(Priority.ERROR))EXCEPTION_LOGGER.error(ace.getMessage(),ace);
				Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"error",(ace.getMessage()!=null?ace.getMessage():"NPE"),"contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			catch(java.lang.Throwable t)
			{
				ctx.status(500);
				ctx.result(t.getMessage());
				EXCEPTION_LOGGER.error(t.getMessage(),t);
				Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"error",(t.getMessage()!=null?t.getMessage():"NPE"),"contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			finally
			{
			}
		}
		else
		{
			ctx.status(500);
			ctx.result("general error");
		}
	}
}
