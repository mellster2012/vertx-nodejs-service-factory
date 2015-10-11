package com.bodhi.vertx.nodejs;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.impl.IsolatingClassLoader;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.VerticleFactory;
import io.vertx.lang.js.JSVerticleFactory;

import org.apache.commons.io.FilenameUtils;
import org.mozilla.javascript.ContextFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * @author marcoellwanger
 */
public class NodeJSVerticleFactory extends JSVerticleFactory {
  
  private static final Logger log = LoggerFactory.getLogger(NodeJSVerticleFactory.class);

  private static final boolean NODEJS_VERTICLES_ENABLED = checkNodeJSDependencies();

  private static final String NODEJS_VERTICLES_DISABLED = "Resolution of node.js verticles disabled";
	private static final String APIGEE_TRIREME_MISSING = "io.apigee.trireme:trireme-core, io.apigee.trireme:trireme-kernel, io.apigee.trireme:trireme-node10src, io.apigee.trireme:trireme-node12src, io.apigee.trireme:trireme-crypto, io.apigee.trireme:trireme-util v0.8.6 required on the classpath";
	private static final String COMMONS_IO_MISSING = "commons-io:commons-io v2.4 required on the classpath";
	private static final String RHINO_MISSING = "org.mozilla:rhino v1.7.7 required on the classpath";

	@Override
	public boolean requiresResolve() {
		return true;
	}
	
	@Override
  public void resolve(String identifier, DeploymentOptions deploymentOptions, ClassLoader classLoader, Future<String> resolution) {
    if (! NODEJS_VERTICLES_ENABLED) resolution.fail(NODEJS_VERTICLES_DISABLED);
    else if (! IsolatingClassLoader.class.isAssignableFrom(classLoader.getClass())) resolution.fail("Isolating classloader required");
    else if (! isNodeJS(classLoader)) resolution.fail("package.json with node engines entry or node_modules directory required");
    else resolution.complete(identifier);
  }
	
  @Override
  public Verticle createVerticle(String verticleName, ClassLoader classLoader) throws Exception {
    return new NodeJSVerticle(VerticleFactory.removePrefix(verticleName), classLoader);
  }
  
  public class NodeJSVerticle extends AbstractVerticle {
  	
  	private final NodeEnvironment env;	
  	private final NodeScript script;
  	
  	private final String verticleName;

    private NodeJSVerticle(String verticleName, ClassLoader loader) throws Exception {
    	this.verticleName = verticleName;
    	env = new NodeEnvironment();
      String normalizedVerticleName = FilenameUtils.normalize(verticleName);
    	log.info("Starting NodeJSVerticle " + verticleName);
      String jar = getJarPath(loader.getResource(normalizedVerticleName));
      Path path = new File(jar.substring(0, jar.lastIndexOf('.'))).toPath();
      StringBuffer buf = new StringBuffer();
      Files.readAllLines(path.resolve(normalizedVerticleName)).forEach(line -> buf.append(line));
      log.info("Resolving " + path.resolve(normalizedVerticleName).toString());
      script = env.createScript(verticleName, path.resolve(normalizedVerticleName).toFile(), null);
    }
    
    @Override
    public void start(Future<Void> startFuture) throws Exception {
      ScriptFuture status = script.execute();
      // Wait for the script to complete
      status.setListener(new ScriptStatusListener() {
				@Override
        public void onComplete(NodeScript script, ScriptStatus status) {
					log.info("Execution status for " + verticleName + ": " + status.getExitCode());
					if (status.hasCause()) log.info("Cause:" + status.getCause().toString());
        }
      });
      startFuture.complete();
    }
    
    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
    	script.close();
    	stopFuture.complete();
    }
  }
  
  private boolean isNodeJS(ClassLoader loader) {
    URL url = loader.getResource("package.json");
  	if (url == null || (! hasNodeModules(url) && ! hasEngines(loader))) return false;
    try { 
    	unzip(url);
    } catch (IOException ex) {
    	log.warn("Cannot extract " + url, ex);
    	return false;
    }
    return true;
}
  
  private boolean hasNodeModules(URL url) {
  	JarEntry modules = null;
  	try {
  		modules = getJarEntry(url, "node_modules");
  	} catch (IOException ex) {
  		log.warn("Cannot read " + url, ex.toString());
  		return false;
  	}
  	return modules != null && (modules.isDirectory() || modules.getSize() == 0);
  }
  
  private boolean hasEngines(ClassLoader loader) {
    StringBuilder buf = new StringBuilder();
  	BufferedReader br = new BufferedReader(new InputStreamReader(loader.getResourceAsStream("package.json")));
  	br.lines().forEach(line -> buf.append(line));
  	JsonObject json = new JsonObject(buf.toString());
  	return json.containsKey("engines") && json.getJsonObject("engines").containsKey("node");
  }
  
	private static void unzip(URL url) throws IOException {
		JarURLConnection connection = (JarURLConnection) url.openConnection();
		JarFile jar = connection.getJarFile();
		Path path = new File(jar.getName()).toPath();
		unzip(jar, path.getParent() == null ? path : path.getParent());
	}	
	
	private static void unzip(JarFile jar, Path dest) throws IOException {
		dest = dest.resolve(jar.getName().substring(0, jar.getName().lastIndexOf('.')));
		if (Files.exists(dest)) rmdir(dest);
		Files.createDirectories(dest);
		Enumeration<JarEntry> entries = jar.entries();
		while (entries.hasMoreElements()) {
			JarEntry e = entries.nextElement();
			if (e.isDirectory()) {
				Path path = dest.resolve(e.getName());
				if (Files.exists(path) && !Files.isDirectory(path)) Files.delete(path);
				Files.createDirectories(path);
			}
			else {
				InputStream is = new BufferedInputStream(jar.getInputStream(e));
				Files.copy(is, dest.resolve(e.getName()), StandardCopyOption.REPLACE_EXISTING);
				is.close();
			}
    }
	}
	
	private static void rmdir(Path directory) throws IOException {
    Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
      }
  
      @Override
      public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
      }
    });
	}
	
	private static String getJarPath(URL url) throws IOException {
		JarURLConnection connection = (JarURLConnection) url.openConnection();
		JarFile jar = connection.getJarFile();
		return jar.getName();
	}
	
	private static JarEntry getJarEntry(URL url, String name) throws IOException {
		JarURLConnection connection = (JarURLConnection) url.openConnection();
		JarFile jar = connection.getJarFile();
		return jar.getJarEntry(name);
	}
  
  private static boolean checkNodeJSDependencies() {
  	try {
  		Class.forName("io.apigee.trireme.core.NodeEnvironment");
  		Class.forName("io.apigee.trireme.core.NodeScript");
  		Class.forName("io.apigee.trireme.core.ScriptFuture");
  		Class.forName("io.apigee.trireme.core.ScriptStatus");
  		Class.forName("io.apigee.trireme.core.ScriptStatusListener");  		
  	} catch (ClassNotFoundException ex) {
    	log.warn(APIGEE_TRIREME_MISSING + " => " + NODEJS_VERTICLES_DISABLED);
  		return false;
  	}
  	try {
  		Class.forName("org.apache.commons.io.FilenameUtils");
  	} catch (ClassNotFoundException ex) {
    	log.warn(COMMONS_IO_MISSING + " => " + NODEJS_VERTICLES_DISABLED);
  		return false;
  	}
    String version = new ContextFactory().enterContext().getImplementationVersion();
    if (! version.startsWith("Rhino 1.7.7 ")) {
    	log.warn(RHINO_MISSING + " => " + NODEJS_VERTICLES_DISABLED);
    	return false;
  	}
    return true;
  }
}
