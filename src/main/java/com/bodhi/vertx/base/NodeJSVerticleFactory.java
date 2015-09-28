package com.bodhi.vertx.base;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.spi.VerticleFactory;
import io.vertx.lang.js.ClasspathFileResolver;

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

import org.apache.commons.io.FilenameUtils;

import io.apigee.trireme.core.NodeEnvironment;
import io.apigee.trireme.core.NodeScript;
import io.apigee.trireme.core.ScriptFuture;
import io.apigee.trireme.core.ScriptStatus;
import io.apigee.trireme.core.ScriptStatusListener;

/**
 * @author marcoellwanger
 * 
 * Experimental verticle factory to detect and run node.js projects loaded by service
 * factories such as  vertx-http-service-factory or vertx-maven-service-factory
 */
public class NodeJSVerticleFactory implements VerticleFactory {
	
	private static NodeEnvironment env = new NodeEnvironment();
	
  static {
    ClasspathFileResolver.init();
  }
  
	private final Logger log = LoggerFactory.getLogger(NodeJSVerticleFactory.class);
	
  private Vertx vertx;

  @Override
  public void init(Vertx vertx) {
    this.vertx = vertx;
  }

  @Override
  public String prefix() {
    return "js";
  }

  @Override
  public Verticle createVerticle(String verticleName, ClassLoader classLoader) throws Exception {
    init();
    String name = FilenameUtils.normalize(verticleName);
    if (name == null) throw new IllegalArgumentException("Illegal verticle name: " + verticleName);
    log.info("Creating NodeJSVerticle for " + name);
    return new NodeJSVerticle(VerticleFactory.removePrefix(name), classLoader);
  }
  
  @Override
  public int order() {
    return -1; // Needs to go before JSVerticleFactory
  }

  @Override
  public boolean requiresResolve() {
    return true;
  }
  
  @Override
  public void resolve(String identifier, DeploymentOptions deploymentOptions, ClassLoader loader, Future<String> resolution) {
  	log.info("Resolving " + identifier);
    URL url = loader.getResource("package.json");
  	if (url == null) resolution.fail("Not Found: package.json");
    else if (hasNodeModules(url) || hasEngines(loader)) {
    	try {
    		Utils.unzip(url);
    	} catch (IOException ex) {
    		resolution.fail(ex.toString());
    		return;
    	}
    	resolution.complete(identifier);
  	} else resolution.fail("Not Found: node_modules directory or node engine property value inside package.json");
  }

  public class NodeJSVerticle extends AbstractVerticle {
    private static final String VERTX_START_FUNCTION = "vertxStart";
    private static final String VERTX_START_ASYNC_FUNCTION = "vertxStartAsync";
    private static final String VERTX_STOP_FUNCTION = "vertxStop";
    private static final String VERTX_STOP_ASYNC_FUNCTION = "vertxStopAsync";

    private final String verticleName;

    private NodeJSVerticle(String verticleName, ClassLoader classLoader) throws Exception {
      this.verticleName = verticleName;
      String jar = Utils.getJarPath(classLoader.getResource(verticleName));
      Path path = new File(jar.substring(0, jar.lastIndexOf('.'))).toPath();
      StringBuffer buf = new StringBuffer();
      Files.readAllLines(path.resolve(verticleName)).forEach(line -> buf.append(line));
      log.info("Resolving " + path.resolve(verticleName).toString());
      NodeScript script = env.createScript(verticleName, path.resolve(verticleName).toFile(), null);

      ScriptFuture status = script.execute();
      // Wait for the script to complete
      status.setListener(new ScriptStatusListener() {
				@Override
        public void onComplete(NodeScript script, ScriptStatus status) {
					log.info("Execution status for " + verticleName + ": " + status.getExitCode());
					if (status.hasCause()) log.info("Cause:" + status.getCause().toString());
        }
      });
      log.info("NodeJSVerticle " + verticleName + " resolved at " + path.resolve(verticleName).toString());
    }
    
    @Override
    public void start(Future<Void> startFuture) throws Exception {
    	log.info("Starting " + verticleName);
      startFuture.complete();
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
    	log.info("Stopping " + verticleName);
      stopFuture.complete();
    }
  }

  private synchronized void init() {
  	
  }
  
  private boolean hasNodeModules(URL url) {
  	JarEntry modules = null;
  	try {
  		modules = Utils.getJarEntry(url, "node_modules");
  	} catch (IOException ex) {
  		log.warn(ex.toString());
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
  
  private static class Utils {
  	public static void unzip(URL url) throws IOException {
  		JarURLConnection connection = (JarURLConnection) url.openConnection();
  		JarFile jar = connection.getJarFile();
  		Path path = new File(jar.getName()).toPath();
  		unzip(jar, path.getParent() == null ? path : path.getParent());
  	}	
  	
  	public static void unzip(JarFile jar, Path dest) throws IOException {
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
  	
  	public static void rmdir(Path directory) throws IOException {
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
  	
  	public static String getJarPath(URL url) throws IOException {
  		JarURLConnection connection = (JarURLConnection) url.openConnection();
  		JarFile jar = connection.getJarFile();
  		return jar.getName();
  	}
  	
  	public static JarEntry getJarEntry(URL url, String name) throws IOException {
  		JarURLConnection connection = (JarURLConnection) url.openConnection();
  		JarFile jar = connection.getJarFile();
  		return jar.getJarEntry(name);
  	}
  }
}
