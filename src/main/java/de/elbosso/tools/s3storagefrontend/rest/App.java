package de.elbosso.tools.s3storagefrontend.rest;

import com.amazonaws.AmazonClientException;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.model.*;
import io.javalin.Javalin;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.influx.InfluxConfig;
import io.micrometer.influx.InfluxMeterRegistry;
import org.apache.log4j.Level;
import org.apache.log4j.Priority;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;

public class App {
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(App.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");

	public static void main(String[] args)
	{
		de.elbosso.util.Utilities.configureBasicStdoutLogging(Level.ALL);
		InfluxConfig config = new InfluxConfig() {
			java.util.Properties props;

			@Override
			public Duration step() {
				return Duration.ofSeconds(10);
			}

			@Override
			public String db() {
				return "monitoring";
			}

			@Override
			public String get(String k) {
				if(props==null)
				{
					props = new java.util.Properties();
					java.net.URL url=de.netsysit.util.ResourceLoader.getResource("influxdb_micrometer.properties");
					if(url==null)
						CLASS_LOGGER.error("could not load default influxdb monitoring properties!");
					else
					{
						try
						{
							java.io.InputStream is = url.openStream();
							props.load(is);
							is.close();
						}
						catch(java.io.IOException exp)
						{
							EXCEPTION_LOGGER.error(exp.getMessage(),exp);
						}
					}
				}
				String rv=System.getenv(k)!=null?System.getenv(k):props.getProperty(k);
				if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("getting value of "+k+": "+rv);
				return rv;
			}
		};
		MeterRegistry registry = new InfluxMeterRegistry(config, Clock.SYSTEM);
		Metrics.globalRegistry.add(registry);
		App.init();
	}
	private static final Javalin init()
	{
		Javalin app = Javalin.create().start(7000);
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("started app - listening on port 7000");
		app.config.addStaticFiles("/site");
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for static contents: /site (allowed methods: GET)");
		app.get("/download/:uuid", ctx -> {
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
				S3Object objectPortion = s3Client.getObject(rangeObjectRequest);

				java.io.InputStream is=objectPortion.getObjectContent();
				java.io.ByteArrayOutputStream baos=new java.io.ByteArrayOutputStream();
				de.elbosso.util.Utilities.copyBetweenStreams(is,baos,true);
				byte[] content=baos.toByteArray();
				ctx.status(201);
				ctx.contentType(objectPortion.getObjectMetadata().getContentType());
				ctx.header("Content-Disposition","filename=\""+objectPortion.getObjectMetadata().getContentDisposition()+"\"");
				ctx.result(new java.io.ByteArrayInputStream(content));
				Metrics.counter("s3storagefrontend.get", "resourcename","/download","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
			else
			{
				ctx.status(500);
				if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("request is not multipart/form-data ");
				Metrics.counter("s3storagefrontend.get", "resourcename","/download","httpstatus","500","error","uuid not set","remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
			}
		});
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for download: /download/<id> (allowed methods: GET)");
		app.post("/upload", ctx -> {
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("received upload request");
			byte[] data=null;
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
							.withExpirationInDays(3650)
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
					s3Client.putObject(request);
					ctx.status(201);
					ctx.contentType("text/html");
					//ctx.header("Content-Disposition","filename=\"reply.tsr\"");
					//ctx.result(new java.io.ByteArrayInputStream(tsr));
					java.lang.String href=ctx.fullUrl().substring(0,ctx.fullUrl().length()-"upload".length())+"download/"+fileObjKeyName;
					ctx.header("Content-Location",href);
					ctx.result("<html><head>" +
							"<link href=\""+href+"\" rel=\"item\" type=\""+metadata.getContentType()+"\" />" +
							"</head><body>" +
							"<a href=\""+href+"\">Download/Share</a>" +
							"</body></html>");
					Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"success","true","contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
				}
				catch (AmazonServiceException ase)
				{
					ctx.status(500);
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
					if(CLASS_LOGGER.isEnabledFor(Priority.ERROR))CLASS_LOGGER.error("Caught an AmazonClientException, which " + "means the client encountered " + "an internal error while trying to "
							+ "communicate with S3, " + "such as not being able to access the network.");
					if(EXCEPTION_LOGGER.isEnabledFor(Priority.ERROR))EXCEPTION_LOGGER.error(ace.getMessage(),ace);
					Metrics.counter("s3storagefrontend.post", "resourcename","/upload","httpstatus",java.lang.Integer.toString(ctx.status()),"error",(ace.getMessage()!=null?ace.getMessage():"NPE"),"contentType",contentType,"remoteAddr",ctx.req.getRemoteAddr(),"remoteHost",ctx.req.getRemoteHost(),"localAddr",ctx.req.getLocalAddr(),"localName",ctx.req.getLocalName()).increment();
				}
				catch(java.lang.Throwable t)
				{
					ctx.status(500);
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
			}
		});
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for storing data: /upload (allowed methods: POST)");
		app.before(ctx -> {
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug(ctx.req.getMethod()+" "+ctx.contentType());
		});
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added before interceptor");
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			app.stop();
		}));
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added callback for stopping the application");
		app.events(event -> {
			event.serverStopping(() -> { /* Your code here */ });
			event.serverStopped(() -> {
				if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("Server stopped");
			});
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added listener for server stopped event");
		});
		return app;
	}
}
