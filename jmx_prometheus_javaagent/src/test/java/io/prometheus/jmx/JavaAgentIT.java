package io.prometheus.jmx;

import static org.hamcrest.MatcherAssert.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;

public class JavaAgentIT {
    private List<URL> getClassloaderUrls() {
        return getClassloaderUrls(getClass().getClassLoader());
    }

    private static List<URL> getClassloaderUrls(ClassLoader classLoader) {
        if (classLoader == null) {
            return Collections.emptyList();
        }
        if (!(classLoader instanceof URLClassLoader)) {
            return getClassloaderUrls(classLoader.getParent());
        }
        URLClassLoader u = (URLClassLoader) classLoader;
        List<URL> result = new ArrayList<URL>(Arrays.asList(u.getURLs()));
        result.addAll(getClassloaderUrls(u.getParent()));
        return result;
    }

    private String buildClasspath() {
        StringBuilder sb = new StringBuilder();
        for (URL url : getClassloaderUrls()) {
            if (!url.getProtocol().equals("file")) {
                continue;
            }
            if (sb.length() != 0) {
                sb.append(java.io.File.pathSeparatorChar);
            }
            sb.append(url.getPath());
        }
        return sb.toString();
    }

    @Test
    public void agentLoads() throws IOException, InterruptedException, GeneralSecurityException {
        // If not starting the testcase via Maven, set the buildDirectory and finalName system properties manually.
        final String buildDirectory = (String) System.getProperties().get("buildDirectory");
        final String finalName = (String) System.getProperties().get("finalName");
        final int port = Integer.parseInt((String) System.getProperties().get("it.port"));
        final String config = resolveRelativePathToResource("test.yml");
        final File file = File.createTempFile("server-config", ".yml");
        BufferedWriter bw = new BufferedWriter(new FileWriter(file));
        bw.write("serverTLSEnabled: true\n");
        bw.write("serverKeyStorePath: "+ resolveRelativePathToResource("ks.p12") +"\n");
        bw.write("serverKeyStorePassword: ykOUaInleG/fqTPi\n");
        bw.close();
        final String javaagent = "-javaagent:" + buildDirectory + "/" + finalName + ".jar=" + port + ":" + config + "," + file.getAbsolutePath();

        final String javaHome = System.getenv("JAVA_HOME");
        final String java;
        if (javaHome != null && javaHome.equals("")) {
            java = javaHome + "/bin/java";
        } else {
            java = "java";
        }

        final Process app = new ProcessBuilder()
            .command(java, javaagent, "-cp", buildClasspath(), "io.prometheus.jmx.TestApplication")
            .start();
        try {
            // Wait for application to start
            app.getInputStream().read();
            setupTrustStores();
            InputStream stream = new URL("https://localhost:" + port + "/metrics").openStream();
            BufferedReader contents = new BufferedReader(new InputStreamReader(stream));
            boolean found = false;
            while (!found) {
                String line = contents.readLine();
                if (line == null) {
                    break;
                }
                if (line.contains("jmx_scrape_duration_seconds")) {
                    found = true;
                }
            }

            assertThat("Expected metric not found", found);
            // Tell application to stop
            app.getOutputStream().write('\n');
            try {
                app.getOutputStream().flush();
            } catch (IOException ignored) {
            }
        } finally {
            final int exitcode = app.waitFor();
            // Log any errors printed
            int len;
            byte[] buffer = new byte[100];
            while ((len = app.getErrorStream().read(buffer)) != -1) {
                System.out.write(buffer, 0, len);
            }

            assertThat("Application did not exit cleanly", exitcode == 0);
        }
    }

    //trying to avoid the occurrence of any : in the windows path
    private String resolveRelativePathToResource(String resource) {
        final String configwk = new File(getClass().getClassLoader().getResource(resource).getFile()).getAbsolutePath();
        final File workingDir = new File(new File(".").getAbsolutePath());
        return "." + configwk.replace(workingDir.getParentFile().getAbsolutePath(), "");
    }

    private void setupTrustStores() throws GeneralSecurityException {
        TrustManager[] trustAllCerts = new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
                    throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
                    throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        // Install the all-trusting trust manager
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAllCerts, new java.security.SecureRandom());

        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String s, SSLSession sslSession) {
                return true;
            }
        });
    }
}
