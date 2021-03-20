package de.elbosso.tools.s3storagefrontend.rest;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.BucketLifecycleConfiguration;
import de.elbosso.tools.s3storagefrontend.rest.handlers.DeletionHandler;
import de.elbosso.tools.s3storagefrontend.rest.handlers.DownloadHandler;
import de.elbosso.tools.s3storagefrontend.rest.handlers.UploadHandler;
import io.javalin.Javalin;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.influx.InfluxMeterRegistry;
import org.apache.log4j.Level;

import java.util.Arrays;

public class App {
	private final static java.lang.String RULEID="de.elbosso.tools.s3storagefrontend.rest.App.RULEID";
	private static final java.lang.String EXPIRATIONENVKEY="de.elbosso.tools.s3storagefrontend.rest.App.expirationInDays";
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(App.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");

	public static void main(String[] args)
	{
		de.elbosso.util.Utilities.configureBasicStdoutLogging(Level.ALL);
		io.micrometer.influx.InfluxConfig config = new de.elbosso.tools.s3storagefrontend.monitoring.InfluxConfig();
		MeterRegistry registry = new InfluxMeterRegistry(config, Clock.SYSTEM);
		Metrics.globalRegistry.add(registry);
		App.init();
	}
	private static final Javalin init()
	{
		Regions clientRegion = Regions.DEFAULT_REGION;
		AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
//							.withRegion(clientRegion)
				.withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(System.getenv("AWS_S3_URL"), clientRegion.name()))
				.withPathStyleAccessEnabled(true)
//							.withClientConfiguration(clientConfiguration)
				.build();
		String bucketName = System.getenv("AWS_BUCKET_NAME");

		BucketLifecycleConfiguration blcc=s3Client.getBucketLifecycleConfiguration(bucketName);
		//get Rules for Bucket
		java.util.List<BucketLifecycleConfiguration.Rule> rules=blcc.getRules();
		if(rules==null)
			rules=new java.util.LinkedList();
		BucketLifecycleConfiguration.Rule expirationRule = null;
		for(BucketLifecycleConfiguration.Rule rule:rules)
		{
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug(rule.getId());
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug(rule.getExpirationDate());
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug(rule.getExpirationInDays());
			if(RULEID.equals(rule.getId()))
			{
				expirationRule=rule;
				break;
			}
		}
		if(expirationRule!=null)
			rules.remove(expirationRule);

		int expi=1;
		java.lang.String expirationInDays=System.getenv(EXPIRATIONENVKEY);
		if(expirationInDays!=null)
		{
			try
			{
				expi = java.lang.Integer.parseInt(expirationInDays);
			}
			catch(java.lang.NumberFormatException exp)
			{
				if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("could not parse value of "+EXPIRATIONENVKEY+" ("+expirationInDays+") - using the default value 1!");
				expi=1;
			}
		}
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug(EXPIRATIONENVKEY+" not found - using the default value 1!");
		if(expi<1)
		{
			if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("values of less than one day are not allowed for the time until expiration - using the default value of 1!");
			expi=1;
		}
		// Create a rule to delete objects after the specified number of days.
		// The rule applies to all objects with the tag "archive" set to "true".
		expirationRule = new BucketLifecycleConfiguration.Rule()
				.withId(RULEID)
				.withExpirationInDays(expi)
				.withStatus(BucketLifecycleConfiguration.ENABLED);
		rules.add(expirationRule);

		// Add the rules to a new BucketLifecycleConfiguration.
		blcc = new BucketLifecycleConfiguration().withRules(rules);

		// Save the configuration.
		s3Client.setBucketLifecycleConfiguration(bucketName, blcc);

		Javalin app = Javalin.create().start(7000);
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("started app - listening on port 7000");
		app.config.addStaticFiles("/site");
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for static contents: /site (allowed methods: GET)");
		DownloadHandler.register(app);
		UploadHandler.register(app);
		DeletionHandler.register(app);
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
