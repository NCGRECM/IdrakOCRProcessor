package com.dataserve.ocr;

import com.dataserve.ocr.tasks.StarterTask;

public class TaskScheduler {
	
	public static void main(String[] args) {
		// This line is to improve performance on java versions that are prior to 1.8.0_191 or 9.0.4
		if (isOptimizationConfigurationNeeded()) {		
			System.setProperty("sun.java2d.cmm", "sun.java2d.cmm.kcms.KcmsServiceProvider");
		}
		new StarterTask().run();
//        long delay = 1 * 60 * 1000;               // start immediately
//        long period = 10 * 60 * 1000;  // 5 minutes in milliseconds
//
//        // Schedule the task to run every 10 minutes at a fixed rate , regardless of how long the task takes.
//		//new java.util.Timer().scheduleAtFixedRate(new StarterTask(), delay, period);
//		// Schedule the task to run every 10 minutes at a fixed rate , Subsequent executions are delayed if previous execution took too long.
//		new java.util.Timer().schedule(new StarterTask(), delay, period);
    }
	
	private static boolean isOptimizationConfigurationNeeded() {
		String version = System.getProperty("java.version");
		String[] versionParts = version.split("\\.");
		float minorVersion = Float.parseFloat(versionParts[versionParts.length - 1].replace("_", ""));
		if (version.startsWith("1")) {
			// older versions than 9
			if (!version.startsWith("1.8")) {
				return false;
			}
			return (minorVersion >= 0.191 ? true : false);
		} else {
			// newer versions (9+)
			if (!version.startsWith("9")) {
				return false;
			}
			return (minorVersion >= 4 ? true : false);
		}
	}
}
