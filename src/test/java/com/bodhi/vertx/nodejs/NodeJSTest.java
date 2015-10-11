package com.bodhi.vertx.nodejs;

import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class NodeJSTest {
	
	@Test
	public void testNodeJS(TestContext context) {
		Async async = context.async();
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle("nodejs:target/test-classes/examples/http-server.zip", ar -> {
      if (ar.succeeded()) {
      	vertx.close();
        System.out.println("Succeeded in deploying");
      } else {
      	vertx.close();
        System.out.println("Failed: " + ar.cause());
        ar.cause().printStackTrace();
        context.fail(ar.cause());
      }
      async.complete();
    });
  }
}
