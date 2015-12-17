# README #

This README describes how to run node.js modules as verticles inside vert.x-x3.

### Setup ###

* Download [vert.x 3.2.0] (http://vertx.io) and unpack
* If your distro contains a (org.mozilla) rhino version other than 1.7.7 inside /lib then remove the jar or rename it so it doesn't get included in the classpath
* run mvn clean install on this project
* From your local .m2/repository, **either**
 * copy com/bodhi/vertx/vertx-nodejs-service-factory/3.1.0-SNAPSHOT/vertx-nodejs-service-factory-3.1.0-SNAPSHOT-fat.jar into /lib (shaded-jar option) **or**
 * copy commons-io/commons-io/2.4/commons-io-2.4.jar, io/apigee/trireme/trireme-core/0.8.6/trireme-core-0.8.6.jar, io/apigee/trireme/trireme-node10src/0.8.6/trireme-node10src-0.8.6.jar, io/apigee/trireme/trireme-node12src/0.8.6/trireme-node12src-0.8.6.jar, io/apigee/trireme/trireme-crypto/0.8.6/trireme-crypto-0.8.6.jar, io/apigee/trireme/trireme-util/0.8.6/trireme-util-0.8.6.jar, org/mozilla/rhino/1.7.7/rhino-1.7.7.jar, com/bodhi/vertx/vertx-nodejs-service-factory/3.1.0-SNAPSHOT/vertx-nodejs-service-factory-3.1.0-SNAPSHOT.jar into /lib (non-shaded-jar option)

### Test ###

* Copy the provided http-server.zip node.js module (located inside src/test/resources/examples) into a location of your choice (don't unzip)
* If that location is served by a webserver, you can test it by using the http-service-factory, e.g.: bin/vertx run http://127.0.0.1:8080/http-server.zip::package
* If that location is just on your local fs, you can test it by using the nodejs-service-factory, e.g. bin/vertx run nodejs:/some/directory/http-server.zip

* node.js modules need to be zipped at their root (not including the module-name directory) and occasionally very slightly modified wrt their startup scripts, e.g. if the main script is provided under bin, this js file needs to be specified as value for the main property inside package.json or if the value of the main property inside package.json is lacking the proper .js extension, the .js extension may have to be added when resolving via service factories other than NodeJSServiceFactory such as vertx-http-service-factory.
