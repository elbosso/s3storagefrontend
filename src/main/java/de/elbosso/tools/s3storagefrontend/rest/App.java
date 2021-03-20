package de.elbosso.tools.s3storagefrontend.rest;

import de.elbosso.tools.s3storagefrontend.rest.handlers.DeletionHandler;
import de.elbosso.tools.s3storagefrontend.rest.handlers.DownloadHandler;
import de.elbosso.tools.s3storagefrontend.rest.handlers.UploadHandler;
import io.javalin.Javalin;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.influx.InfluxConfig;
import io.micrometer.influx.InfluxMeterRegistry;
import org.apache.log4j.Level;

public class App {
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
		Javalin app = Javalin.create().start(7000);
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("started app - listening on port 7000");
		app.config.addStaticFiles("/site");
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for static contents: /site (allowed methods: GET)");
		app.get("/download/:uuid", new DownloadHandler());
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for download: /download/<id> (allowed methods: GET)");
		app.post("/upload", new UploadHandler());
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for storing data: /upload (allowed methods: POST)");
		app.delete("/delete/:uuid", new DeletionHandler());
		if(CLASS_LOGGER.isDebugEnabled())CLASS_LOGGER.debug("added path for deletion: /delete/<id> (allowed methods: DELETE)");
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
