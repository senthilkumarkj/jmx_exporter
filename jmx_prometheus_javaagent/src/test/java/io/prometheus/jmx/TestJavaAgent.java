package io.prometheus.jmx;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import io.prometheus.jmx.JavaAgent.Config;
import java.io.File;
import java.io.FileNotFoundException;
import org.junit.Assert;
import org.junit.Test;

public class TestJavaAgent {
    /**
     * Test that the agent string argument is parsed properly. We expect the agent argument in one of these forms...
     * <pre>
     * {@code <port>:<yaml configuration file>}
     * </pre>
     * <pre>
     * {@code <host>:<port>:<yaml configuration file>}
     * </pre>
     * Since the ':' character is part of the spec for this arg, Windows-style paths could cause an issue with parsing.
     * See https://github.com/prometheus/jmx_exporter/issues/312.
     */
    @Test
    public void testAgentStringParsing() throws Exception {
        final String DEFAULT_HOST = "0.0.0.0";
        final File resourcesDirectory = new File("src/test/resources");
        final String serverConfig = resourcesDirectory.getAbsolutePath() + "/test-server.yml";
        final String tlsServerConfig = resourcesDirectory.getAbsolutePath() + "/test-server-tls.yml";

        JavaAgent.Config config = JavaAgent.parseConfig("8080:config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals(DEFAULT_HOST, config.host);
        Assert.assertEquals("config.yaml", config.file);
        Assert.assertEquals(8080, config.port);
        Assert.assertFalse("expect tls disabled", config.tlsEnabled);

        config = JavaAgent.parseConfig("8080:/unix/path/config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals(DEFAULT_HOST, config.host);
        Assert.assertEquals("/unix/path/config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgent.parseConfig("google.com:8080:/unix/path/config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals("google.com", config.host);
        Assert.assertEquals("/unix/path/config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgent.parseConfig("127.0.0.1:8080:/unix/path/config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals("127.0.0.1", config.host);
        Assert.assertEquals("/unix/path/config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgent.parseConfig("8080:\\Windows\\Local\\Drive\\Path\\config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals(DEFAULT_HOST, config.host);
        Assert.assertEquals("\\Windows\\Local\\Drive\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        // the following check was previously failing to parse the file correctly
        config = JavaAgent.parseConfig("8080:C:\\Windows\\Path\\config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals(DEFAULT_HOST, config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        // the following check was previously failing to parse the file correctly
        config = JavaAgent.parseConfig("google.com:8080:C:\\Windows\\Path\\config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals("google.com", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        // the following check was previously failing to parse the file correctly
        config = JavaAgent.parseConfig("127.0.0.1:8080:C:\\Windows\\Path\\config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals("127.0.0.1", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgent.parseConfig("8080:config.yaml," + tlsServerConfig, DEFAULT_HOST);
        Assert.assertTrue("expect tls enabled", config.tlsEnabled);
        Assert.assertEquals("keystore path","/tmp/ks.p12", config.keyStorePath);
        Assert.assertEquals("password", "secret", new String(config.keyStorePassword));
    }

    /**
     * If someone is specifying an ipv6 address and a host name, this should be rejected.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidInput() throws Exception {
        JavaAgent.parseConfig("[2001:0db8:0000:0042:0000:8a2e:0370:7334]:localhost:8080:config.yaml,server-config.yaml", "0.0.0.0");
    }

    /**
     * Similarly to the test above, two host names
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRejectInvalidInput2() throws Exception {
        JavaAgent.parseConfig("127.0.0.1:localhost:8080:config.yaml", "0.0.0.0");
    }

    /**
     * Exercise the existing Ipv6 parsing logic. The current logic leaves the brackets on the host.
     */
    @Test
    public void testIpv6AddressParsing() throws Exception {
        final String DEFAULT_HOST = "0.0.0.0";
        final String serverConfig = new File("src/test/resources").getAbsolutePath() + "/test-server.yml";

        JavaAgent.Config config = JavaAgent.parseConfig("[1:2:3:4]:8080:config.yaml," + serverConfig, DEFAULT_HOST);
        Assert.assertEquals("[1:2:3:4]", config.host);
        Assert.assertEquals("config.yaml", config.file);
        Assert.assertEquals(8080, config.port);

        config = JavaAgent.parseConfig("[2001:0db8:0000:0042:0000:8a2e:0370:7334]:8080:C:\\Windows\\Path\\config.yaml,"+serverConfig, DEFAULT_HOST);
        Assert.assertEquals("[2001:0db8:0000:0042:0000:8a2e:0370:7334]", config.host);
        Assert.assertEquals("C:\\Windows\\Path\\config.yaml", config.file);
        Assert.assertEquals(8080, config.port);
    }

    @Test(expected = FileNotFoundException.class)
    public void testServerConfigInvalidFile() throws Exception {
        JavaAgent.parseConfig("localhost:8080:config.yaml,foo.yaml", "0.0.0.0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testServerConfigInvalidFileContent() throws Exception {
        final String tlsServerInvalidConfig = new File("src/test/resources").getAbsolutePath() + "/test-server-tls-invalid.yml";
        JavaAgent.parseConfig("localhost:8080:config.yaml," + tlsServerInvalidConfig, "0.0.0.0");
    }

    @Test
    public void testCreateHttpsServer() throws Exception {
        final String DEFAULT_HOST = "0.0.0.0";
        final String tlsServerConfig = new File("src/test/resources").getAbsolutePath() + "/test-server-tls.yml";

        Config config = JavaAgent.parseConfig("0:config.yaml," + tlsServerConfig, DEFAULT_HOST);
        config.keyStorePath = new File("src/test/resources").getAbsolutePath() + "/ks.p12";
        config.keyStorePassword = "ykOUaInleG/fqTPi".toCharArray();
        System.out.println(config.keyStorePath + " " + new String(config.keyStorePassword));

        HttpServer server = JavaAgent.createHttpsServer(config);
        Assert.assertTrue("expect HttpsServer instance", server instanceof HttpsServer);
        Assert.assertNotNull("expect https config", ((HttpsServer) server).getHttpsConfigurator());
        server.stop(0);
    }
}
