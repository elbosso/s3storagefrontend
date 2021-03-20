package de.elbosso.tools.s3storagefrontend.monitoring;

import java.time.Duration;

public class InfluxConfig extends java.lang.Object implements io.micrometer.influx.InfluxConfig
{
	private final static org.apache.log4j.Logger CLASS_LOGGER=org.apache.log4j.Logger.getLogger(InfluxConfig.class);
	private final static org.apache.log4j.Logger EXCEPTION_LOGGER=org.apache.log4j.Logger.getLogger("ExceptionCatcher");
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
}
