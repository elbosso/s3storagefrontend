package de.elbosso.tools.s3storagefrontend.rest.handlers;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.micrometer.core.instrument.Metrics;
import org.apache.log4j.Priority;
import org.jetbrains.annotations.NotNull;

public class DeletionHandler extends Object implements Handler
{
	final static java.lang.String RESOURCENAME="delete";
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(DeletionHandler.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");

	public static void register(Javalin app)
	{
		app.delete("/"+RESOURCENAME+"/:uuid", new DeletionHandler());
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for deletion: /"+RESOURCENAME+"/<id> (allowed methods: DELETE)");
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
			if(s3Client.doesObjectExist(bucketName, fileObjKeyName))
			{
				try
				{
					s3Client.deleteObject(bucketName, fileObjKeyName);
					ctx.status(200);
					Metrics.counter("s3storagefrontend.get", "resourcename", "/"+RESOURCENAME, "remoteAddr", ctx.req.getRemoteAddr(), "remoteHost", ctx.req.getRemoteHost(), "localAddr", ctx.req.getLocalAddr(), "localName", ctx.req.getLocalName()).increment();
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
		else
		{
			ctx.status(500);
			ctx.result("request is not multipart/form-data ");
			if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("request is not multipart/form-data ");
			Metrics.counter("s3storagefrontend.get", "resourcename","/"+RESOURCENAME,"httpstatus","500","error","uuid not set","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
		}
	}
}
