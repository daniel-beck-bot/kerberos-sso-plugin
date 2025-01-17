/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.sonymobile.jenkins.plugins.kerberossso;

import com.google.inject.Inject;
import hudson.remoting.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.impl.auth.BasicSchemeFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jenkinsci.test.acceptance.FallbackConfig;
import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.guice.TestCleaner;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.DockerTest;
import org.jenkinsci.test.acceptance.junit.FailureDiagnostics;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.configuration_as_code.JcascManage;
import org.jenkinsci.test.acceptance.po.GlobalSecurityConfig;
import org.jenkinsci.test.acceptance.po.JenkinsDatabaseSecurityRealm;
import org.jenkinsci.test.acceptance.po.PageAreaImpl;
import org.jenkinsci.test.acceptance.po.User;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.model.Statement;
import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.firefox.GeckoDriverService;
import org.openqa.selenium.remote.UnreachableBrowserException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;

/**
 * Run Kerberos SSO tests against the containerized KDC.
 */
@WithPlugins({"kerberos-sso", "mailer"})
@Category(DockerTest.class)
@WithDocker
public class KerberosSsoTest extends AbstractJUnitTest {
    private static final String AUTHORIZED = "Username: user; Password: [PROTECTED]; Authenticated: true; Details: null; Granted Authorities: authenticated";

    @Inject
    public DockerContainerHolder<KerberosContainer> kerberos;

    @Inject
    public FailureDiagnostics diag;

    @Inject
    public TestCleaner cleaner;

    @Test
    public void kerberosTicket() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingPos(kdc, false, false);

        verifyTicketAuth(kdc);

        // The global driver is not configured to do so
        driver.manage().deleteAllCookies(); // Logout
        jenkins.visit("/whoAmI"); // 401 Unauthorized
        assertThat(driver.getPageSource(), not(containsString(AUTHORIZED)));
    }

    @Test
    public void kerberosTicketWithBasicAuthEnabled() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingPos(kdc, false, true);

        verifyTicketAuth(kdc);
    }

    @Test
    public void kerberosTicketWithBasicAuthEnabledJcasc() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingJcasc(kdc, false, true);

        verifyTicketAuth(kdc);
    }

    private void verifyTicketAuth(KerberosContainer kdc) throws IOException, InterruptedException {
        // Get TGT
        String tokenCache = kdc.getClientTokenCache();

        // Correctly negotiate in browser
        FirefoxDriver negotiatingDriver = getNegotiatingFirefox(kdc, tokenCache);

        //visit the page who requires authorization and asks for credentials before visiting unprotected root action "/whoAmI"
        negotiatingDriver.get(jenkins.url.toExternalForm());

        negotiatingDriver.get(jenkins.url("/whoAmI").toExternalForm());
        String out = negotiatingDriver.getPageSource();
        assertThat(out, containsString(AUTHORIZED));

        // Non-negotiating request should fail
        assertUnauthenticatedRequestIsRejected(getBadassHttpClient());
    }

    @Test
    public void basicAuth() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingPos(kdc, false, true);

        CloseableHttpClient httpClient = getBadassHttpClient();

        // No credentials provided
        assertUnauthenticatedRequestIsRejected(httpClient);

        // Correct credentials provided
        HttpGet get = new HttpGet(jenkins.url.toExternalForm() + "/whoAmI");
        get.setHeader("Authorization", "Basic " + Base64.encode("user:ATH".getBytes()));
        CloseableHttpResponse response = httpClient.execute(get);
        String phrase = response.getStatusLine().getReasonPhrase();
        String out = IOUtils.toString(response.getEntity().getContent());
        assertThat(phrase + ": " + out, out, containsString("Full Name"));
        assertThat(phrase + ": " + out, out, containsString("Granted Authorities: authenticated"));
        assertEquals(phrase + ": " + out, "OK", phrase);

        //reset client
        httpClient = getBadassHttpClient();
        // Incorrect credentials provided
        get = new HttpGet(jenkins.url.toExternalForm() + "/whoAmI");
        get.setHeader("Authorization", "Basic " + Base64.encode("user:WRONG_PASSWD".getBytes()));
        response = httpClient.execute(get);
        assertEquals("Invalid password/token for user: user", response.getStatusLine().getReasonPhrase());
    }

    @Test
    public void explicitTicketAuth() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingPos(kdc, true, true);

        String tokenCache = kdc.getClientTokenCache();
        FirefoxDriver nego = getNegotiatingFirefox(kdc, tokenCache);

        assertNegotiationWorking(nego);
    }

    private void assertNegotiationWorking(FirefoxDriver nego) {
        nego.get(jenkins.url("/whoAmI").toExternalForm());
        assertThat(nego.getPageSource(), not(containsString(AUTHORIZED)));

        nego.get(jenkins.url("/login").toExternalForm());
        nego.get(jenkins.url("/whoAmI").toExternalForm());
        assertThat(nego.getPageSource(), containsString(AUTHORIZED));
    }

    @Test
    public void explicitBasicAuth() throws Exception {
        setupRealmUser();
        KerberosContainer kdc = startKdc();
        configureSsoUsingPos(kdc, true, true);

        assertAnonymousWithoutCredentials();
        assertLoggedInWithCorrectCredentials();
        assertRejectedWithIncorrectCredentials();
    }

    private void assertRejectedWithIncorrectCredentials() throws IOException {
        HttpGet get = new HttpGet(jenkins.url.toExternalForm() + "/login");
        get.setHeader("Authorization", "Basic " + Base64.encode("user:WRONG_PASSWD".getBytes()));
        CloseableHttpResponse response = getBadassHttpClient().execute(get);
        assertEquals("Invalid password/token for user: user", response.getStatusLine().getReasonPhrase());
    }

    private void assertLoggedInWithCorrectCredentials() throws IOException {
        HttpGet get = new HttpGet(jenkins.url.toExternalForm() + "/login");
        get.setHeader("Authorization", "Basic " + Base64.encode("user:ATH".getBytes()));
        CloseableHttpResponse response = getBadassHttpClient().execute(get);
        String phrase = response.getStatusLine().getReasonPhrase();
        String out = IOUtils.toString(response.getEntity().getContent());
        assertThat(phrase + ": " + out, out, containsString("Full Name"));
        //assertThat(phrase + ": " + out, out, containsString("Granted Authorities: authenticated"));
        assertEquals(phrase + ": " + out, "OK", phrase);
    }

    private void assertAnonymousWithoutCredentials() throws IOException {
        HttpGet get = new HttpGet(jenkins.url.toExternalForm() + "/whoAmI");
        CloseableHttpResponse response = getBadassHttpClient().execute(get);
        String out = IOUtils.toString(response.getEntity().getContent());
        assertThat(out, not(containsString("Granted Authorities: authenticated")));
        assertThat(out, containsString("Anonymous"));
    }

    private FirefoxDriver getNegotiatingFirefox(KerberosContainer kdc, String tokenCache) {
        FirefoxOptions firefoxOptions = new FirefoxOptions();
        FirefoxProfile profile = new FirefoxProfile();
        profile.setAlwaysLoadNoFocusLib(true);
        // Allow auth negotiation for jenkins under test
        String url = jenkins.url.toExternalForm();
        if(url.endsWith("/")){
            url = url.substring(0, url.length()-1);
        }
        String trustedUris = url;
        String jenkins_local_hostname = System.getenv("JENKINS_LOCAL_HOSTNAME");
        // if JENKINS_LOCAL_HOSTNAME is set, we add this to FF nego uris
        if (jenkins_local_hostname != null && !jenkins_local_hostname.isEmpty()) {
            try {
                // In the case where JENKINS_LOCAL_HOSTNAME is an IP,
                // we need to add its resolved hostname for auth negociation
                String hostName = InetAddress.getByName(jenkins_local_hostname).getCanonicalHostName();
                trustedUris = trustedUris + ", " + hostName;
            } catch (UnknownHostException e) {
                e.printStackTrace();
                throw new Error(e);
            }
        }
        profile.setPreference("network.negotiate-auth.trusted-uris", trustedUris);
        profile.setPreference("network.negotiate-auth.delegation-uris", trustedUris);

        FirefoxBinary binary = new FirefoxBinary();
        Map<String,String> environment = new HashMap<>();
        // Inject config and TGT
        environment.put("KRB5CCNAME", tokenCache);
        environment.put("KRB5_CONFIG", kdc.getKrb5ConfPath());
        // Turn debug on
        environment.put("KRB5_TRACE", diag.touch("krb5_trace.log").getAbsolutePath());
        environment.put("NSPR_LOG_MODULES", "negotiateauth:5");
        environment.put("NSPR_LOG_FILE", diag.touch("firefox.nego.log").getAbsolutePath());

        String display = FallbackConfig.getBrowserDisplay();
        if (display != null) {
            environment.put("DISPLAY", display);
        }
        GeckoDriverService.Builder builder = new GeckoDriverService.Builder();
        builder.usingDriverExecutable(new File(System.getProperty(GeckoDriverService.GECKO_DRIVER_EXE_PROPERTY))).usingFirefoxBinary(binary).withEnvironment(environment);
        //builder.usingFirefoxBinary(binary);
        firefoxOptions.setBinary(binary);
        firefoxOptions.setProfile(profile);
        final FirefoxDriver driver = new FirefoxDriver(builder.build(), firefoxOptions);
        cleaner.addTask(new Statement() {
            @Override
            public void evaluate() {
                try {
                    driver.quit();
                } catch (UnreachableBrowserException ex) {
                    System.err.println("Browser died already");
                    ex.printStackTrace();
                }
            }

            @Override public String toString() {
                return "Close Kerberos WebDriver after test";
            }
        });
        return driver;
    }

    private void assertUnauthenticatedRequestIsRejected(CloseableHttpClient httpClient) throws IOException {
        HttpGet get = new HttpGet(jenkins.url.toExternalForm());
        CloseableHttpResponse response = httpClient.execute(get);
        assertEquals("Unauthorized", response.getStatusLine().getReasonPhrase());
        assertEquals("Negotiate", response.getHeaders("WWW-Authenticate")[0].getValue());
    }

    /**
     * HTTP client that does not negotiate.
     */
    // I am not able to get the basic auth to work in FF 45.3.0, so using HttpClient instead
    // org.openqa.selenium.UnsupportedCommandException: Unrecognized command: POST /session/466a800f-eaf8-40cf-a9e8-815f5a6e3c32/alert/credentials
    // alert.setCredentials(new UserAndPassword("user", "ATH"));
    private CloseableHttpClient getBadassHttpClient() {
        return HttpClientBuilder.create().setDefaultAuthSchemeRegistry(
                RegistryBuilder.<AuthSchemeProvider>create()
                    .register(AuthSchemes.BASIC, new BasicSchemeFactory())
                    .build()
        ).build();
    }

    /**
     * Turn the SSO on in Jenkins.
     *
     * @param allowAnonymous Require authentication on all URLs.
     * @param allowBasic Allow basic authentication.
     */
    private void configureSsoUsingPos(KerberosContainer kdc, boolean allowAnonymous, boolean allowBasic) {
        // Turn Jenkins side debugging on
        jenkins.runScript("System.setProperty('sun.security.krb5.debug', 'true'); System.setProperty('sun.security.spnego.debug', 'true');");

        GlobalSecurityConfig s = new GlobalSecurityConfig(jenkins);
        s.configure();
        KerberosGlobalConfig kgc = new KerberosGlobalConfig(s);
        kgc.enable();
        kgc.krb5Conf(kdc.getKrb5ConfPath());
        kgc.loginConf(kdc.getLoginConfPath());
        kgc.allowLocalhost(false);
        kgc.allowBasic(allowBasic);
        kgc.allowAnonymous(allowAnonymous);

        s.save();
    }

    private void configureSsoUsingJcasc(KerberosContainer kdc, boolean allowAnonymous, boolean allowBasic) throws IOException {
        JcascManage page = new JcascManage(jenkins);
        Path decl = Files.createTempFile("kerberos-sso", "jcasc");
        decl.toFile().deleteOnExit();
        try (PrintWriter w = new PrintWriter(decl.toFile())) {
            w.println("security:");
            w.println("  kerberosSso:");
            w.println("    enabled: true");
            w.println("    krb5Location: " + kdc.getKrb5ConfPath());
            w.println("    loginLocation: " + kdc.getLoginConfPath());
            w.println("    allowLocalhost: false");
            w.println("    allowBasic: " + allowBasic);
            w.println("    anonymousAccess: " + allowAnonymous);
        }

        page.open();
        page.configure(decl.toAbsolutePath().toString());
    }

    /**
     * Start KDC container populating target dir with generated keytabs and config files.
     */
    private KerberosContainer startKdc() throws IOException {
        KerberosContainer kdc = kerberos.get();
        File target = Files.createTempDirectory(getClass().getSimpleName()).toFile();
        kdc.populateTargetDir(target);
        return kdc;
    }

    /**
     * Create KDC user in backend realm.
     *
     * It is necessary the backend realm recognises all the users kerberos let in. The user is logged in when this method completes.
     */
    private User setupRealmUser() {
        // Populate realm with users
        GlobalSecurityConfig sc = new GlobalSecurityConfig(jenkins);
        sc.configure();
        JenkinsDatabaseSecurityRealm realm = sc.useRealm(JenkinsDatabaseSecurityRealm.class);
        sc.save();
        // The password needs to be the same as in kerberos
        return realm.signup().password("ATH").fullname("Full Name")
                .email("ath@ath.com")
                .signup("user");
    }

    private class KerberosGlobalConfig extends PageAreaImpl {
        public KerberosGlobalConfig(GlobalSecurityConfig config) {
            super(config, "/com-sonymobile-jenkins-plugins-kerberossso-PluginImpl");
        }

        public KerberosGlobalConfig enable() {
            control("enabled").check();
            control("enabled/advanced-button").click();
            return this;
        }

        public KerberosGlobalConfig krb5Conf(String krb5) {
            control("enabled/krb5Location").set(krb5);
            return this;
        }

        public KerberosGlobalConfig loginConf(String krb5) {
            control("enabled/loginLocation").set(krb5);
            return this;
        }

        public KerberosGlobalConfig allowLocalhost(boolean allow) {
            control("enabled/allowLocalhost").check(allow);
            return this;
        }

        public KerberosGlobalConfig allowBasic(boolean allow) {
            control("enabled/allowBasic").check(allow);
            return this;
        }

        public KerberosGlobalConfig allowAnonymous(boolean login) {
            control("enabled/anonymousAccess").check(login);
            return this;
        }
    }
}
