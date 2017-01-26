/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2011-2016 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2016 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.smoketest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.junit.Rule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriver.Timeouts;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.phantomjs.PhantomJSDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriverService;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.io.Files;
import com.thoughtworks.selenium.SeleniumException;

public class OpenNMSSeleniumTestCase {
    private static final Logger LOG = LoggerFactory.getLogger(OpenNMSSeleniumTestCase.class);
    private static final String APACHE_LOG_LEVEL = "INFO"; // change this to help debug smoke tests

    private static final boolean setLevel(final String pack, final String level) {
        final Logger logger = org.slf4j.LoggerFactory.getLogger(pack);
        if (logger instanceof ch.qos.logback.classic.Logger) {
            ((ch.qos.logback.classic.Logger) logger).setLevel(ch.qos.logback.classic.Level.valueOf(level));
            return true;
        }
        return false;
    }

    static {
        final String logLevel = System.getProperty("org.opennms.smoketest.logLevel", "DEBUG");

        /* Set up the mock log appender, if it is in the classpath */
        final Properties props = new Properties();
        props.put("log4j.logger.org.apache.http", APACHE_LOG_LEVEL);
        try {
            final Class<?> mockLogAppender = Class.forName("org.opennms.core.test.MockLogAppender");
            if (mockLogAppender != null) {
                final Method[] methods = mockLogAppender.getMethods();
                for (final Method method : methods) {
                    System.err.println("method=" + method);
                }
                final Method m = mockLogAppender.getMethod("setupLogging", Boolean.TYPE, String.class, Properties.class);
                if (m != null) {
                    m.invoke(null, true, logLevel, props);
                }
            }
        } catch (final ClassNotFoundException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }

        /* Set up apache commons directly, if possible */
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.SimpleLog");
        System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", APACHE_LOG_LEVEL);

        /* Set up logback, if it's there */
        setLevel(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME, logLevel);
        setLevel("org.apache.http", APACHE_LOG_LEVEL);
    }

    static {
        final File chromeDriver = findChromeDriver();
        if (chromeDriver != null) {
            LOG.debug("Found chrome driver: " + chromeDriver.getAbsolutePath());
            System.setProperty("webdriver.chrome.driver", chromeDriver.getAbsolutePath());
        } else {
            LOG.debug("Did not find chrome driver.");
        }
    }

    public static final long   LOAD_TIMEOUT       = Long.getLong("org.opennms.smoketest.web-timeout", 120000l);
    public static final long   REQ_TIMEOUT        = Long.getLong("org.opennms.smoketest.requisition-timeout", 240000l);
    public static final String OPENNMS_WEB_HOST   = System.getProperty("org.opennms.smoketest.web-host", "localhost");
    public static final int    OPENNMS_WEB_PORT   = Integer.getInteger("org.opennms.smoketest.web-port", 8980);
    public static final String OPENNMS_EVENT_HOST = System.getProperty("org.opennms.smoketest.event-host", OPENNMS_WEB_HOST);
    public static final int    OPENNMS_EVENT_PORT = Integer.getInteger("org.opennms.smoketest.event-port", 5817);

    public static final String BASIC_AUTH_USERNAME = "admin";
    public static final String BASIC_AUTH_PASSWORD = "admin";

    public static final String BASE_URL           = "http://" + OPENNMS_WEB_HOST + ":" + OPENNMS_WEB_PORT + "/";
    public static final String REQUISITION_NAME   = "SeleniumTestGroup";
    public static final String USER_NAME          = "SmokeTestUser";
    public static final String GROUP_NAME         = "SmokeTestGroup";

    protected static final boolean usePhantomJS = Boolean.getBoolean("org.opennms.smoketest.webdriver.use-phantomjs") || Boolean.getBoolean("smoketest.usePhantomJS");
    protected static final boolean useChrome    = Boolean.getBoolean("org.opennms.smoketest.webdriver.use-chrome");

    protected WebDriver m_driver = null;
    protected WebDriverWait wait = null;
    protected WebDriverWait requisitionWait = null;

    @Rule
    public TestWatcher m_watcher = new TestWatcher() {
        @Override
        protected void starting(final Description description) {
            m_driver = getDriver();
            LOG.debug("Using driver: {}", m_driver);
            setImplicitWait();
            m_driver.manage().window().setPosition(new Point(0,0));
            m_driver.manage().window().setSize(new Dimension(2048, 10000));
            wait = new WebDriverWait(m_driver, TimeUnit.SECONDS.convert(LOAD_TIMEOUT, TimeUnit.MILLISECONDS));
            requisitionWait = new WebDriverWait(m_driver, TimeUnit.SECONDS.convert(REQ_TIMEOUT, TimeUnit.MILLISECONDS));

            m_driver.get(BASE_URL + "opennms/login.jsp");

            // Wait until the login form is complete
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("j_username")));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.name("j_password")));
            wait.until(ExpectedConditions.elementToBeClickable(By.name("Login")));

            enterText(By.name("j_username"), BASIC_AUTH_USERNAME);
            enterText(By.name("j_password"), BASIC_AUTH_PASSWORD);
            findElementByName("Login").click();

            wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath("//div[@id='content']")));
            try {
                // Disable implicitlyWait
                setImplicitWait(0, TimeUnit.MILLISECONDS);
                try {
                    // Make sure that the 'login-attempt-failed' element is not present
                    findElementById("login-attempt-failed");
                    fail("Login failed: " + findElementById("login-attempt-failed-reason").getText());
                } catch (NoSuchElementException e) {
                    // This is expected
                }
            } finally {
                setImplicitWait();
            }

            // make sure everything's in a good state if possible
            cleanUp();
        }

        @Override
        protected void failed(final Throwable e, final Description description) {
            final String testName = description.getMethodName();
            LOG.debug("Test {} failed... attempting to take screenshot.", testName);
            if (m_driver != null && m_driver instanceof TakesScreenshot) {
                final TakesScreenshot shot = (TakesScreenshot)m_driver;
                try {
                    final File from = shot.getScreenshotAs(OutputType.FILE);
                    final String screenshotFileName = "target" + File.separator + "screenshots" + File.separator + description.getClassName() + "." + testName + ".png";
                    final File to = new File(screenshotFileName);
                    LOG.debug("Screenshot saved to: {}", from);
                    try {
                        to.getParentFile().mkdirs();
                        Files.move(from, to);
                        LOG.debug("Screenshot moved to: {}", to);
                    } catch (final IOException ioe) {
                        LOG.debug("Failed to move screenshot from {} to {}", from, to, ioe);
                    }
                } catch (final Exception sse) {
                    LOG.debug("Failed to take screenshot.", sse);
                }
            } else {
                LOG.debug("Driver can't take screenshots.");
            }
            LOG.debug("Current URL: {}", m_driver.getCurrentUrl());
            m_driver.navigate().back();
            LOG.debug("Previous URL: {}", m_driver.getCurrentUrl());
            LogEntries logs = m_driver.manage().logs().get("browser");
            for (final LogEntry entry : logs.getAll()) {
                final int level = entry.getLevel().intValue();
                final String msg = "BROWSER: " + entry.getMessage();
                if (level > Level.SEVERE.intValue()) {
                    LOG.error(msg);
                } else if (level >= Level.WARNING.intValue()) {
                    LOG.warn(msg);
                } else if (level >= Level.INFO.intValue()) {
                    LOG.info(msg);
                } else if (level >= Level.FINE.intValue()) {
                    LOG.debug(msg);
                } else {
                    LOG.trace(msg);
                }
            }
        }

        @Override
        protected void finished(final Description description) {
            cleanUp();

            LOG.debug("Shutting down Selenium.");
            if (m_driver != null) {
                try {
                    m_driver.get(BASE_URL + "opennms/j_spring_security_logout");
                } catch (final SeleniumException e) {
                    // don't worry about it, this is just for logging out
                }
                try {
                    m_driver.quit();
                } catch (final Exception e) {
                    LOG.error("Failed while shutting down WebDriver for test {}.", description.getMethodName(), e);
                }
                m_driver = null;
            }

            try {
                Thread.sleep(3000);
            } catch (final InterruptedException e) {
            }
        }

        protected void cleanUp() {
            try {
                deleteTestRequisition();
                deleteTestUser();
                deleteTestGroup();
            } catch (final Exception e) {
                LOG.error("Cleaning up failed. Future tests will be in an unhandled state.", e);
            }
        }
    };

    protected JavascriptExecutor getExecutor() throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return (JavascriptExecutor)getDriver();
    }

    protected WebDriver getDriver() {
        if (m_driver != null) {
            return m_driver;
        }
        WebDriver driver = null;
        final String driverClass = System.getProperty("org.opennms.smoketest.webdriver.class", System.getProperty("webdriver.class"));
        if (driverClass != null) {
            try {
                driver = (WebDriver)Class.forName(driverClass).newInstance();
            } catch (final InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                throw new OpenNMSTestException(e);
            }
        }

        // otherwise, PhantomJS if found, or fall back to Firefox
        if (driver == null) {
            if (usePhantomJS) {
                final File phantomJS = findPhantomJS();
                if (phantomJS != null) {
                    final DesiredCapabilities caps = DesiredCapabilities.phantomjs();
                    customizeCapabilities(caps);
                    caps.setCapability(PhantomJSDriverService.PHANTOMJS_EXECUTABLE_PATH_PROPERTY, phantomJS.toString());
                    driver = new PhantomJSDriver(caps);
                }
            } else if (useChrome) {
                final File chrome = findChrome();
                if (chrome != null) {
                    final ChromeOptions options = new ChromeOptions();
                    options.setBinary(chrome);
                    final DesiredCapabilities caps = DesiredCapabilities.chrome();
                    customizeCapabilities(caps);
                    caps.setCapability(ChromeOptions.CAPABILITY, options);
                    driver = new ChromeDriver(caps);
                }
            }
            if (driver == null) { // fallback to firefox
                FirefoxProfile fp = new FirefoxProfile();
                fp.setEnableNativeEvents(false);
                fp.setPreference("app.update.auto", false);
                fp.setPreference("app.update.enabled", false);
                fp.setPreference("app.update.silent", false);
                fp.setPreference("browser.startup.homepage", "about:blank");
                fp.setPreference("startup.homepage_welcome_url", "about:blank");
                fp.setPreference("startup.homepage_welcome_url.additional", "about:blank");
                final DesiredCapabilities caps = DesiredCapabilities.firefox();
                customizeCapabilities(caps);
                driver = new FirefoxDriver(new FirefoxBinary(), fp, caps);
            }
        }
        return driver;
    }

    // Hook to customize the behaviour of the Webdriver, as tests might need this functionality
    // and it is not possible to change the capabilities AFTER the webdriver was created.
    protected void customizeCapabilities(DesiredCapabilities caps) {

    }

    private static File findChrome() {
        final String os = System.getProperty("os.name").toLowerCase();
        final String extension = (os.indexOf("win") >= 0)? ".exe" : "";

        final String path = System.getenv("PATH");
        if (path == null) {
            LOG.debug("findChrome(): Unable to get PATH.");
            final File chromeFile = new File("/usr/bin/chromium-browser" + extension);
            LOG.debug("findChrome(): trying {}", chromeFile);
            if (chromeFile.exists() && chromeFile.canExecute()) {
                return chromeFile;
            }
        } else {
            final List<String> paths = new ArrayList<String>(Arrays.asList(path.split(File.pathSeparator)));
            paths.add("/usr/local/bin");
            paths.add("/usr/local/sbin");
            LOG.debug("findChrome(): paths = {}", paths);
            for (final String directory : paths) {
                for (final String exeName : new String[] { "chromium-browser", "chrome", "google-chrome" }) {
                    final File chromeFile = new File(directory + File.separator + exeName + extension);
                    LOG.debug("findChrome(): trying {}", chromeFile);
                    if (chromeFile.exists() && chromeFile.canExecute()) {
                        return chromeFile;
                    }
                }
            }
        }
        return null;
    }

    private static File findChromeDriver() {
        final String os = System.getProperty("os.name").toLowerCase();
        final String extension = (os.indexOf("win") >= 0)? ".exe" : "";

        final String path = System.getenv("PATH");
        if (path == null) {
            LOG.debug("findChromeDriver(): Unable to get PATH.");
            for (final String searchPath : new String[] { "/usr/lib/chromium-browser/", "/usr/local/bin/", "/usr/bin/" }) {
                final File chromeDriverFile = new File(searchPath + "chromedriver" + extension);
                LOG.debug("findChromeDriver(): trying {}", chromeDriverFile);
                if (chromeDriverFile.exists() && chromeDriverFile.canExecute()) {
                    return chromeDriverFile;
                }
            }
        } else {
            final List<String> paths = new ArrayList<String>(Arrays.asList(path.split(File.pathSeparator)));
            paths.add("/usr/local/bin");
            paths.add("/usr/local/sbin");
            paths.add("/usr/lib/chromium-browser");
            LOG.debug("findChromeDriver(): paths = {}", paths);
            for (final String directory : paths) {
                final File chromeDriverFile = new File(directory + File.separator + "chromedriver" + extension);
                LOG.debug("findChromeDriver(): trying {}", chromeDriverFile);
                if (chromeDriverFile.exists() && chromeDriverFile.canExecute()) {
                    return chromeDriverFile;
                }
            }
        }
        return null;
    }

    private static File findPhantomJS() {
        final String os = System.getProperty("os.name").toLowerCase();
        final String extension = (os.indexOf("win") >= 0)? ".exe" : "";

        final String path = System.getenv("PATH");
        if (path == null) {
            LOG.debug("findPhantomJS(): Unable to get PATH.");
            final File phantomFile = new File("/usr/local/bin/phantomjs" + extension);
            LOG.debug("findPhantomJS(): trying {}", phantomFile);
            if (phantomFile.exists() && phantomFile.canExecute()) {
                return phantomFile;
            }
        } else {
            final List<String> paths = new ArrayList<String>(Arrays.asList(path.split(File.pathSeparator)));
            paths.add("/usr/local/bin");
            paths.add("/usr/local/sbin");
            LOG.debug("findPhantomJS(): paths = {}", paths);
            for (final String directory : paths) {
                final File phantomFile = new File(directory + File.separator + "phantomjs" + extension);
                LOG.debug("findPhantomJS(): trying {}", phantomFile);
                if (phantomFile.exists() && phantomFile.canExecute()) {
                    return phantomFile;
                }
            }
        }
        return null;
    }

    protected Timeouts setImplicitWait() {
        return setImplicitWait(LOAD_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    protected Timeouts setImplicitWait(final long time, final TimeUnit unit) {
        LOG.trace("Setting implicit wait to {} milliseconds.", unit.toMillis(time));
        return m_driver.manage().timeouts().implicitlyWait(time, unit);
    }

    protected WebDriverWait waitFor(final long seconds) {
        return new WebDriverWait(m_driver, seconds);
    }

    protected void waitForClose(final By selector) {
        LOG.debug("waitForClose: {}", selector);
        try {
            setImplicitWait(1, TimeUnit.SECONDS);
            wait.until(new ExpectedCondition<Boolean>() {
                @Override
                public Boolean apply(final WebDriver input) {
                    try {
                        Thread.sleep(200);
                        final List<WebElement> elements = input.findElements(selector);
                        if (elements.size() == 0) {
                            return true;
                        }
                        LOG.debug("waitForClose: matching elements: {}", elements);
                        WebElement element = input.findElement(selector);
                        final Point location = element.getLocation();
                        // recreate it because the browser is funny about timing after the getLocation()
                        element = input.findElement(selector);
                        final Dimension size = element.getSize();
                        if (new Point(0,0).equals(location) && new Dimension(0,0).equals(size)) {
                            LOG.debug("waitForClose: {} element technically exists, but is sized 0,0", element);
                            return true;
                        }
                        LOG.debug("waitForClose: {} element still exists at location {} with size {}: {}", selector, location, size, element.getText());
                        return false;
                    } catch (final NoSuchElementException | StaleElementReferenceException e) {
                        return true;
                    } catch (final Exception e) {
                        LOG.debug("waitForClose: unknown exception", e);
                        throw new OpenNMSTestException(e);
                    }
                }
            });
        } finally {
            setImplicitWait();
        }
    }

    protected ExpectedCondition<Boolean> pageContainsText(final String text) {
        LOG.debug("pageContainsText: {}", text);
        final String escapedText = text.replace("\'", "\\\'");
        return new ExpectedCondition<Boolean>() {
            @Override public Boolean apply(final WebDriver driver) {
                final String xpathExpression = "//*[contains(., '" + escapedText + "')]";
                LOG.debug("XPath expression: {}", xpathExpression);
                try {
                    final WebElement element = driver.findElement(By.xpath(xpathExpression));
                    return element != null;
                } catch (final NoSuchElementException e) {
                    return false;
                }
            }
        };
    }

    public void focusElement(final By by) {
        try {
            Thread.sleep(200);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        waitForElement(by).click();
    }

    public void clearElement(final By by) {
        try {
            Thread.sleep(200);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        waitForElement(by).clear();
    }

    protected void assertElementDoesNotExist(final By by) {
        LOG.debug("assertElementDoesNotExist: {}", by);
        WebElement element = getElementWithoutWaiting(by);
        if (element == null) {
            LOG.debug("Success: element does not exist: {}", by);
            return;
        }
        throw new OpenNMSTestException("Element should not exist, but was found: " + element);
    }

    protected WebElement getElementImmediately(final By by) {
        WebElement element = null;
        try {
            setImplicitWait(0, TimeUnit.MILLISECONDS);
            element = getDriver().findElement(by);
        } catch (final NoSuchElementException e) {
            return null;
        } finally {
            setImplicitWait();
        }
        return element;
    }

    protected WebElement getElementWithoutWaiting(final By by) {
        WebElement element = null;
        try {
            setImplicitWait(2, TimeUnit.SECONDS);
            element = getDriver().findElement(by);
        } catch (final NoSuchElementException e) {
            return null;
        } finally {
            setImplicitWait();
        }
        return element;
    }

    protected void assertElementDoesNotHaveText(final By by, final String text) {
        LOG.debug("assertElementDoesNotHaveText: locator={}, text={}", by, text);
        WebElement element = null;
        try {
            setImplicitWait(2, TimeUnit.SECONDS);
            element = getDriver().findElement(by);
            assertTrue(!element.getText().contains(text));
        } catch (final NoSuchElementException e) {
            LOG.debug("Success: element does not exist: {}", by);
            return;
        } finally {
            setImplicitWait();
        }
    }

    protected void assertElementHasText(final By by, final String text) {
        LOG.debug("assertElementHasText: locator={}, text={}", by, text);
        WebElement element = waitForElement(by);
        assertTrue(element.getText().contains(text));
    }

    protected String handleAlert() {
        return handleAlert(null);
    }

    protected String handleAlert(final String expectedText) {
        LOG.debug("handleAlerm: expectedText={}", expectedText);
        try {
            final Alert alert = m_driver.switchTo().alert();
            final String alertText = alert.getText();
            if (expectedText != null) {
                assertEquals(expectedText, alertText);
            }
            alert.dismiss();
            return alertText;
        } catch (final NoAlertPresentException e) {
            LOG.debug("handleAlert: no alert is active");
        } catch (final TimeoutException e) {
            LOG.debug("handleAlert: no alert was found");
        }
        return null;
    }

    protected void setChecked(final By by) {
        LOG.debug("setChecked: locator=", by);
        final WebElement element = m_driver.findElement(by);
        if (element.isSelected()) {
            return;
        } else {
            element.click();
        }
    }

    protected void setUnchecked(final By by) {
        LOG.debug("setUnchecked: locator=", by);
        final WebElement element = m_driver.findElement(by);
        if (element.isSelected()) {
            element.click();
        } else {
            return;
        }
    }

    protected void clickMenuItem(final String menuItemText, final String submenuItemText, final String submenuItemHref) {
        LOG.debug("clickMenuItem: itemText={}, submenuItemText={}, submenuHref={}", menuItemText, submenuItemText, submenuItemHref);

        final Actions action = new Actions(m_driver);

        final WebElement menuElement;
        if (menuItemText.startsWith("name=")) {
            final String menuItemName = menuItemText.replaceFirst("name=", "");
            menuElement = findElementByName(menuItemName);
        } else {
            menuElement = findElementByXpath("//a[contains(text(), '" + menuItemText + "')]");
        }
        action.moveToElement(menuElement, 2, 2).perform();

        final WebElement submenuElement;
        if (submenuItemText != null) {
            if (submenuItemHref == null) {
                submenuElement = findElementByXpath("//a[contains(text(), '" + submenuItemText + "')]");
            } else {
                submenuElement = findElementByXpath("//a[contains(@href, '" + submenuItemHref + "') and contains(text(), '" + submenuItemText + "')]");
            }
        } else {
            submenuElement = null;
        }

        if (submenuElement == null) {
            // no submenu given, just click the main element
            // wait until the element is visible, not just present in the DOM
            wait.until(ExpectedConditions.visibilityOf(menuElement));
            menuElement.click();
        } else {
            // we want a submenu item, click it instead
            // wait until the element is visible, not just present in the DOM
            wait.until(ExpectedConditions.visibilityOf(submenuElement));
            submenuElement.click();
        }
    }

    protected void frontPage() {
        LOG.debug("navigating to the front page");
        m_driver.get(BASE_URL + "opennms/");
        m_driver.findElement(By.id("index-contentleft"));
    }

    public void adminPage() {
        LOG.debug("navigating to the admin page");
        m_driver.get(BASE_URL + "opennms/admin/index.jsp");
    }

    protected void nodePage() {
        LOG.debug("navigating to the node page");
        m_driver.get(BASE_URL + "opennms/element/nodeList.htm");
    }

    protected void notificationsPage() {
        LOG.debug("navigating to the notifications page");
        m_driver.get(BASE_URL + "opennms/notification/index.jsp");
    }

    protected void outagePage() {
        LOG.debug("navigating to the outage page");
        m_driver.get(BASE_URL + "opennms/outage/index.jsp");
    }

    protected void provisioningPage() {
        LOG.debug("navigating to the provisioning page");
        m_driver.get(BASE_URL + "opennms/admin/index.jsp");
        m_driver.findElement(By.linkText("Manage Provisioning Requisitions")).click();
    }

    protected void remotingPage() {
        LOG.debug("navigating to the remoting page");
        m_driver.get(BASE_URL + "opennms-remoting/index.html");
    }

    protected void reportsPage() {
        LOG.debug("navigating to the reports page");
        m_driver.get(BASE_URL + "opennms/report/index.jsp");
    }

    protected void searchPage() {
        LOG.debug("navigating to the search page");
        m_driver.get(BASE_URL + "opennms/element/index.jsp");
    }

    protected void supportPage() {
        LOG.debug("navigating to the support page");
        m_driver.get(BASE_URL + "opennms/support/index.htm");
    }

    protected void goBack() {
        LOG.warn("hitting the 'back' button");
        m_driver.navigate().back();
    }

    public void clickElement(final By by) {
        waitUntil(new Callable<WebElement>() {
            @Override public WebElement call() throws Exception {
                final WebElement el = getElementImmediately(by);
                el.click();
                return el;
            }
        });
    }

    public WebElement waitForElement(final By by) {
        return waitForElement(wait, by);
    }

    public WebElement waitForElement(final WebDriverWait w, final By by) {
        return waitUntil(null, w, new Callable<WebElement>() {
            @Override public WebElement call() throws Exception {
                final WebElement el = getDriver().findElement(by);
                if (el.isDisplayed() && el.isEnabled()) {
                    return el;
                }
                return null;
            }
        });
    }

    public void enterAutocompleteText(final By textInput, final String text) {
        waitUntil(100L, null, new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                waitForElement(textInput).clear();
                waitForValue(textInput, "");
                waitForElement(textInput).sendKeys(text);
                // Click on the item that appears
                findElementByXpath("//span[text()='" + text + "']").click();
                return true;
            }
        });
    }

    public void clickUntilVaadinPopupAppears(final By by, final String title) {
        waitUntil(100L, null, new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                final WebDriver driver = getDriver();
                WebElement popup = getVaadinPopup(driver, title);

                if (popup == null) {
                    try {
                        LOG.debug("clickUntilVaadinPopupAppears: looking for '{}'", by);
                        final WebElement el = getElementImmediately(by);
                        if (el == null) {
                            LOG.debug("clickUntilVaadinPopupAppears: element not found: {}", by);
                            Thread.sleep(50);
                            return false;
                        } else {
                            LOG.debug("clickUntilVaadinPopupAppears: clicking element: {}", el);
                            el.click();
                            Thread.sleep(50);
                        }
                    } catch (final Throwable t) {
                        LOG.debug("clickUntilVaadinPopupAppears: exception raised while attempting to click {}", by, t);
                        return false;
                    }

                    popup = getVaadinPopup(driver, title);
                    if (popup != null) {
                        return true;
                    }
                } else if (popup.isDisplayed() && popup.isEnabled()) {
                    return true;
                } else {
                    LOG.debug("clickUntilVaadinPopupAppears: popup with title '{}' is gone", title);
                }
                return false;
            }
        });
        waitFor(1);
    }

    public void clickUntilVaadinPopupDisappears(final By by, final String title) {
        waitUntil(100L, null, new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                final WebDriver driver = getDriver();
                WebElement popup = getVaadinPopup(driver, title);

                if (popup != null) {
                    try {
                        LOG.debug("clickIdUntilVaadinPopupDisappears: looking for '{}'", by);
                        final WebElement el = getElementImmediately(by);
                        if (el == null) {
                            LOG.debug("clickIdUntilVaadinPopupDisappears: element not found: {}", by);
                            Thread.sleep(50);
                            return false;
                        } else {
                            LOG.debug("clickIdUntilVaadinPopupDisappears: clicking element: {}", el);
                            el.click();
                            Thread.sleep(50);
                        }
                    } catch (final Throwable t) {
                        LOG.debug("clickUntilVaadinPopupDisappears: exception raised while attempting to click {}", by, t);
                        return false;
                    }

                    popup = getVaadinPopup(driver, title);
                    if (popup == null) {
                        return true;
                    }
                } else {
                    return true;
                }
                return false;
            }
        });
        waitFor(1);
    }

    protected boolean inVaadin() {
        try {
            final WebElement element = getElementImmediately(By.className("v-generated-body"));
            if (element != null) {
                return true;
            }
        } catch (final Exception e) {
        }
        return false;
    }

    protected void selectVaadinFrame() {
        if (!inVaadin()) {
            LOG.debug("Switching to Vaadin frame.");
            getDriver().switchTo().frame(findElementById("vaadin-content"));
        }
    }

    protected void selectDefaultFrame() {
        LOG.debug("Switching to default frame.");
        getDriver().switchTo().defaultContent();
    }

    public WebElement getVaadinPopup(final String title) {
        return getVaadinPopup(getDriver(), title);
    }

    private WebElement getVaadinPopup(final WebDriver driver, final String title) {
        selectVaadinFrame();
        try {
            LOG.debug("Checking for Vaadin popup '{}'", title);
            final By vaadinHeaderXpath = By.xpath("//div[@class='popupContent']//div[contains(text(), '" + title + "') and @class='v-window-header']");
            final WebElement el = getElementImmediately(vaadinHeaderXpath);
            LOG.debug("Found Vaadin popup '{}': {}", title, el.toString());
            return el;
        } catch (final Throwable t) {
            LOG.debug("Did not find Vaadin popup '{}'", title);
            return null;
        }
    }

    public void selectByVisibleText(final String id, final String text) {
        LOG.debug("selectByVisibleText: id={}, text={}", id, text);
        waitUntil(null, null, new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                final Select select = getSelect(id);
                select.selectByVisibleText(text);
                return true;
            }

        });
    }

    /**
     * Vaadin usually wraps the select elements around a div element.
     * This method considers this.
     */
    public Select getSelect(final String id) {
        LOG.debug("Getting <div id='{}'><select />", id);

        final WebElement div = waitUntil(null, null, new Callable<WebElement>() {
            @Override public WebElement call() throws Exception {
                LOG.debug("Searching for id '{}'", id);
                final WebElement el = getElementImmediately(By.id(id));
                if (el != null && el.isDisplayed() && el.isEnabled()) {
                    return el;
                }
                return null;
            }
        });

        LOG.debug("Found id: {} -- looking for select element.", div);
        final WebElement element = div.findElement(By.tagName("select"));
        LOG.debug("Found select: {}", element);
        return new Select(element);
    }

    public WebElement findElementById(final String id) {
        LOG.debug("findElementById: id={}", id);
        return m_driver.findElement(By.id(id));
    }

    public WebElement findElementByLink(final String link) {
        LOG.debug("findElementByLink: link={}", link);
        return m_driver.findElement(By.linkText(link));
    }

    public WebElement findElementByName(final String name) {
        LOG.debug("findElementByName: name={}", name);
        return m_driver.findElement(By.name(name));
    }

    public WebElement findElementByCss(final String css) {
        LOG.debug("findElementByCss: selector={}", css);
        return m_driver.findElement(By.cssSelector(css));
    }

    public WebElement findElementByXpath(final String xpath) {
        LOG.debug("findElementByXpath: selector={}", xpath);
        return m_driver.findElement(By.xpath(xpath));
    }

    public int countElementsMatchingCss(final String css) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        LOG.debug("countElementsMatchingCss: selector={}", css);

        // Selenium has a bug where the findElements(By) doesn't return elements; even if I attempt to do it manually
        // using JavascriptExecutor.execute(), so... parse the DOM on the Java side instead.  :/
        final org.jsoup.nodes.Document doc = Jsoup.parse(m_driver.getPageSource());
        final Elements matching = doc.select(css);
        return matching.size();

        // The original one-line implementation, for your edification.  Look at the majesty!
        // A single tear rolls down your cheek as you imagine what could have been, if
        // Selenium wasn't junk.
        //return getDriver().findElements(By.cssSelector(css)).size();
    }

    /**
     * CAUTION: There are a variety of Firefox-specific bugs related to using
     * {@link WebElement#sendKeys(CharSequence...)}. We're doing this bizarre
     * sequence of operations to try and work around them.
     *
     * @see https://code.google.com/p/selenium/issues/detail?id=2487
     * @see https://code.google.com/p/selenium/issues/detail?id=8180
     */
    protected WebElement enterText(final By selector, final CharSequence... text) {
        final String textString = Joiner.on("").join(text);
        LOG.debug("Enter text: '{}' into selector: '{}'", text, selector);

        // First, attempt to focus on the element before typing
        scrollToElement(selector);
        final WebElement el = waitForElement(selector);
        if (el.isDisplayed() && el.isEnabled()) {
            el.click();
        }
        sleep(500);

        final long end = System.currentTimeMillis() + LOAD_TIMEOUT;
        boolean found = false;
        int count = 0;

        final WebDriverWait shortWait = new WebDriverWait(m_driver, 10);

        do {
            LOG.debug("enterText({},{}): {}", selector, text, ++count);
            // Clear the element content and then confirm it's really clear
            waitForElement(selector).clear();
            waitForValue(selector, "");

            // Click the element to make sure it's still got the focus
            waitForElement(selector).click();
            sleep(500);
            // Send the keys
            waitForElement(selector).sendKeys(text);
            waitForElement(selector).click();
            sleep(500);

            if (text.length == 1 && text[0] != Keys.ENTER) { // special case, carriage-return for a previously-entered entry
                try {
                    final String elementText = waitForElement(shortWait, selector).getText();
                    final String elementValue = waitForElement(shortWait, selector).getAttribute("value");
                    found = elementText.contains(textString) || elementValue.contains(textString);
                } catch (final Exception e) {
                    LOG.warn("Failed when checking for {} to equal '{}'.", selector, textString, e);
                }
            } else {
                LOG.info("Skipped waiting for {} to equal {}", selector, textString);
                found = true;
            }
        } while (!found && System.currentTimeMillis() < end);

        return m_driver.findElement(selector);
    }

    protected void sleep(final int millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            throw new OpenNMSTestException(e);
        }
    }

    protected void waitForValue(final By selector, final String value) {
        wait.until(new Predicate<WebDriver>() {
            @Override public boolean apply(final WebDriver driver) {
                return value.equals(driver.findElement(selector).getAttribute("value"));
            }
        });
    }

    /**
     * @deprecated Use {@link #scrollToElement(By)} instead.
     */
    protected WebElement scrollToElement(final WebElement element) {
        return scrollToElement(getDriver(), element);
    }

    /**
     * @deprecated Use {@link #scrollToElement(By)} instead.
     */
    protected static WebElement scrollToElement(final WebDriver driver, final WebElement element) {
        LOG.debug("scrollToElement: element={}", element);

        final List<Integer> bounds = getBoundedRectangleOfElement(driver, element);
        final int windowHeight = driver.manage().window().getSize().getHeight();
        final JavascriptExecutor je = (JavascriptExecutor)driver;
        je.executeScript("window.scrollTo(0, " + (bounds.get(1) - (windowHeight/2)) + ");");
        return element;
    }

    protected WebElement scrollToElement(final By by) {
        return scrollToElement(by, true);
    }

    protected WebElement scrollToElement(final By by, final boolean waitForElement) {
        LOG.debug("scrollToElement: by={}", by);

        if (waitForElement) {
            try {
                setImplicitWait(200, TimeUnit.MILLISECONDS);
                final WebElement element = wait.until(new ExpectedCondition<WebElement>() {
                    @Override public WebElement apply(final WebDriver driver) {
                        final WebElement el = waitForElement? waitForElement(by) : driver.findElement(by);
                        return doScroll(driver, el);
                    }

                });
                if (element == null) {
                    throw new OpenNMSTestException("Failed to scroll to element: " + by);
                }
                return element;
            } finally {
                setImplicitWait();
            }
        } else {
            final WebDriver driver = getDriver();
            final WebElement el = driver.findElement(by);
            return doScroll(driver, el);
        }
    }

    private WebElement doScroll(final WebDriver driver, final WebElement element) {
        try {
            final List<Integer> bounds = getBoundedRectangleOfElement(driver, element);
            final int windowHeight = driver.manage().window().getSize().getHeight();
            final JavascriptExecutor je = (JavascriptExecutor)driver;
            je.executeScript("window.scrollTo(0, " + (bounds.get(1) - (windowHeight/2)) + ");");
            return element;
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * In some cases, Vaadin doesn't register our clicks,
     * so this method keeps click until the given element
     * is no longer found.
     *
     * @param by selector
     */
    public void clickElementUntilElementDisappears(final By click, final By disappears) {
        waitUntil(100L, null, new Callable<Boolean>() {
            @Override public Boolean call() throws Exception {
                try {
                    final WebElement element = getElementImmediately(disappears);
                    if (element == null) {
                        return true;
                    }
                } catch (final NoSuchElementException e) {
                    return true;
                } catch (final Throwable t) {
                    LOG.warn("clickElementUntilItDisappears: disappearing element not gone yet: click={}, disappears={}", click, disappears, t);
                    return false;
                }
                try {
                    final WebElement element = getElementImmediately(click);
                    if (element != null) {
                        element.click();
                    }
                } catch (final Throwable t) {
                    LOG.warn("clickElementUntilItDisappears: unable to click clickable: click={}, disappears={}", click, disappears, t);
                }
                return false;
            }
        });
    }

    @SuppressWarnings("unchecked")
    protected static List<Integer> getBoundedRectangleOfElement(final WebDriver driver, final WebElement we) {
        LOG.debug("getBoundedRectangleOfElement: element={}", we);
        final JavascriptExecutor je = (JavascriptExecutor)driver;
        final List<String> bounds = (ArrayList<String>) je.executeScript(
                                                                         "var rect = arguments[0].getBoundingClientRect();" +
                                                                                 "return [ '' + parseInt(rect.left), '' + parseInt(rect.top), '' + parseInt(rect.width), '' + parseInt(rect.height) ]", we);
        final List<Integer> ret = new ArrayList<>();
        for (final String entry : bounds) {
            ret.add(Integer.valueOf(entry));
        }
        return ret;
    }

    protected void clickId(final String id) throws InterruptedException {
        clickId(id, true);
    }

    protected void clickId(final String id, final boolean refresh) throws InterruptedException {
        LOG.debug("clickId: id={}, refresh={}", id, refresh);
        WebElement element = null;
        try {
            setImplicitWait(10, TimeUnit.MILLISECONDS);

            try {
                element = findElementById(id);
            } catch (final Throwable t) {
                LOG.warn("Failed to locate id=" + id, t);
            }

            final long waitUntil = System.currentTimeMillis() + 60000;
            while (element == null || element.getAttribute("disabled") != null || !element.isDisplayed() || !element.isEnabled()) {
                if (System.currentTimeMillis() >= waitUntil) {
                    break;
                }
                try {
                    if (refresh) {
                        m_driver.navigate().refresh();
                        //Thread.sleep(2000);
                    }
                    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id(id)));
                    wait.until(ExpectedConditions.elementToBeClickable(By.id(id)));
                    element = findElementById(id);
                } catch (final Throwable t) {
                    LOG.warn("Failed to locate id=" + id, t);
                }
            }
            Thread.sleep(1000);
            element.click();
        } finally {
            setImplicitWait();
        }
    }

    public <T> T waitUntil(final Callable<T> callable) {
        return waitUntil(null, wait, callable);
    }

    public <T> T waitUntil(final WebDriverWait w, final Callable<T> callable) {
        return waitUntil(null, w, callable);
    }

    public <T> T waitUntil(final Long implicitWait, final WebDriverWait w, final Callable<T> callable) {
        final WebDriverWait wdw = w == null? wait : w;
        try {
            setImplicitWait(implicitWait == null? 50 : implicitWait, TimeUnit.MILLISECONDS);
            return wdw.until(new ExpectedCondition<T>() {
                @Override
                public T apply(final WebDriver driver) {
                    try {
                        return callable.call();
                    } catch (final Throwable t) {
                        return null;
                    }
                }
            });
        } finally {
            setImplicitWait();
        }
    }

    protected Integer doRequest(final HttpRequestBase request) throws ClientProtocolException, IOException, InterruptedException {
        return getRequest(request).getStatus();
    }

    protected ResponseData getRequest(final HttpRequestBase request) throws ClientProtocolException, IOException, InterruptedException {
        final CountDownLatch waitForCompletion = new CountDownLatch(1);

        final URI uri = request.getURI();
        final HttpHost targetHost = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(new AuthScope(targetHost.getHostName(), targetHost.getPort()), new UsernamePasswordCredentials(BASIC_AUTH_USERNAME, BASIC_AUTH_PASSWORD));
        AuthCache authCache = new BasicAuthCache();
        // Generate BASIC scheme object and add it to the local auth cache
        BasicScheme basicAuth = new BasicScheme();
        authCache.put(targetHost, basicAuth);

        // Add AuthCache to the execution context
        HttpClientContext context = HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        context.setAuthCache(authCache);

        final CloseableHttpClient client = HttpClients.createDefault();

        final ResponseHandler<ResponseData> responseHandler = new ResponseHandler<ResponseData>() {
            @Override
            public ResponseData handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
                try {
                    final int status = response.getStatusLine().getStatusCode();
                    String responseText = null;
                    // 400 because we return that if you try to delete
                    // something that is already deleted
                    // 404 because it's OK if it's already not there
                    if (status >= 200 && status < 300 || status == 400 || status == 404) {
                        final HttpEntity entity = response.getEntity();
                        if (entity != null) {
                            responseText = EntityUtils.toString(entity);
                            EntityUtils.consume(entity);
                        }
                        final ResponseData r = new ResponseData(status, responseText);
                        return r;
                    } else {
                        throw new ClientProtocolException("Unexpected response status: " + status);
                    }
                } catch (final Exception e) {
                    LOG.warn("Unhandled exception", e);
                    return new ResponseData(-1, null);
                } finally {
                    waitForCompletion.countDown();
                }
            }
        };

        final ResponseData result = client.execute(targetHost, request, responseHandler, context);

        waitForCompletion.await();
        client.close();
        return result;
    }

    public long getNodesInDatabase(final String foreignSource) {
        try {
            final HttpGet request = new HttpGet(BASE_URL + "opennms/rest/nodes?foreignSource=" + URLEncoder.encode(foreignSource, "UTF-8"));
            final ResponseData rd = getRequest(request);
            LOG.debug("getNodesInDatabase: response={}", rd);

            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document document = builder.parse(new InputSource(new StringReader(rd.getResponseText())));
            final Element rootElement = document.getDocumentElement();
            return Long.valueOf(rootElement.getAttribute("totalCount"), 10);
        } catch (final Exception e) {
            throw new OpenNMSTestException(e);
        }
    }

    public boolean requisitionExists(final String foreignSource) {
        LOG.debug("requisitionExists: foreignSource={}", foreignSource);
        try {
            final String foreignSourceUrlFragment = URLEncoder.encode(foreignSource, "UTF-8");
            final Integer status = doRequest(new HttpGet(BASE_URL + "opennms/rest/requisitions/" + foreignSourceUrlFragment));
            return status == 200;
        } catch (final IOException | InterruptedException e) {
            throw new OpenNMSTestException(e);
        }
    }

    public void deleteExistingRequisition(final String foreignSource) {
        LOG.debug("deleteExistingRequisition: Deleting Requisition: {}", foreignSource);

        final long waitUntil = System.currentTimeMillis() + (5 * 60 * 1000);
        do {
            long nodesInRequisition = -1;
            long nodesInDatabase = -1;

            try {
                nodesInRequisition = getNodesInRequisition(foreignSource);
                nodesInDatabase = getNodesInDatabase(foreignSource);

                LOG.debug("deleteExistingRequisition: nodesInRequisition={}, nodesInDatabase={}", nodesInRequisition, nodesInDatabase);

                final String foreignSourceUrlFragment = URLEncoder.encode(foreignSource, "UTF-8");

                if (nodesInDatabase > 0) {
                    createRequisition(foreignSource);
                }

                if (requisitionExists(foreignSource)) {
                    // make sure the requisition is deleted
                    sendDelete("/rest/requisitions/" + foreignSourceUrlFragment);
                    sendDelete("/rest/requisitions/deployed/" + foreignSourceUrlFragment);
                    sendDelete("/rest/foreignSources/" + foreignSourceUrlFragment);
                    sendDelete("/rest/foreignSources/deployed/" + foreignSourceUrlFragment);
                }
                Thread.sleep(1000);
            } catch (final Exception e) {
                throw new OpenNMSTestException(e);
            }
            if (System.currentTimeMillis() > waitUntil) {
                throw new OpenNMSTestException("Gave up waiting to delete requisition '" + foreignSource + "'.  This should totally not happen.");
            }
        } while (requisitionExists(foreignSource));
    }

    @Deprecated
    protected WebElement getForeignSourceElement(final String requisitionName) {
        LOG.debug("getForeignSourceElement: requisition={}", requisitionName);
        final String selector = "//span[@data-foreignSource='" + requisitionName + "']";
        WebElement foreignSourceElement = null;
        try {
            setImplicitWait(2, TimeUnit.SECONDS);
            foreignSourceElement = m_driver.findElement(By.xpath(selector));
        } catch (final NoSuchElementException e) {
            // no match, treat as a no-op
            LOG.debug("Could not find: {}", selector);
            return null;
        } finally {
            setImplicitWait();
        }
        return foreignSourceElement;
    }

    protected void createTestRequisition() {
        createRequisition(REQUISITION_NAME);
    }

    protected void createRequisition(final String foreignSource) {
        LOG.debug("Creating empty requisition: " + foreignSource);
        final String emptyRequisition = "<model-import xmlns=\"http://xmlns.opennms.org/xsd/config/model-import\" date-stamp=\"2013-03-29T11:36:55.901-04:00\" foreign-source=\"" + foreignSource + "\" last-import=\"2016-03-29T10:40:23.947-04:00\"></model-import>";
        createRequisition(foreignSource, emptyRequisition, 0);
    }

    protected void createRequisition(final String foreignSource, final String xml, final int expectedNodes) {
        LOG.debug("Creating requisition from XML: " + foreignSource);
        try {
            final String foreignSourceUrlFragment = URLEncoder.encode(foreignSource, "UTF-8");

            sendPost("/rest/requisitions", xml);
            requisitionWait.until(new WaitForNodesInRequisition(expectedNodes));

            final HttpPut request = new HttpPut(BASE_URL + "opennms/rest/requisitions/" + foreignSourceUrlFragment + "/import");
            final Integer status = doRequest(request);
            if (status == null || status < 200 || status >= 400) {
                throw new OpenNMSTestException("Unknown status: " + status);
            }
            requisitionWait.until(new WaitForNodesInDatabase(expectedNodes));
        } catch (final Exception e) {
            throw new OpenNMSTestException(e);
        }
    }

    protected void deleteTestRequisition() throws Exception {
        deleteExistingRequisition(REQUISITION_NAME);
    }

    protected void createTestForeignSource(final String xml) {
        createForeignSource(REQUISITION_NAME, xml);
    }

    protected void createForeignSource(final String foreignSource, final String xml) {
        LOG.debug("Creating foreign source definition: {}", foreignSource);
        try {
            sendPost("/rest/foreignSources", xml);
            // make sure it gets written to disk
            doRequest(new HttpGet(BASE_URL + "/rest/foreignSources"));
            Thread.sleep(2000);
        } catch (final Exception e) {
            throw new OpenNMSTestException(e);
        }
    }

    protected void deleteTestForeignSource() {
        deleteExistingForeignSource(REQUISITION_NAME);
    }

    protected void deleteExistingForeignSource(final String foreignSource) {
        LOG.debug("Deleting foreign source definition: {}", foreignSource);
        try {
            final String foreignSourceUrlFragment = URLEncoder.encode(foreignSource, "UTF-8");
            sendDelete("/rest/foreignSources/" + foreignSourceUrlFragment);
        } catch (final Exception e) {
            throw new OpenNMSTestException(e);
        }
    }

    protected void deleteTestUser() throws Exception {
        LOG.debug("deleteTestUser()");
        doRequest(new HttpDelete(BASE_URL + "opennms/rest/users/" + USER_NAME));
    }

    protected void deleteTestGroup() throws Exception {
        LOG.debug("deleteTestGroup()");
        doRequest(new HttpDelete(BASE_URL + "opennms/rest/groups/" + GROUP_NAME));
    }

    protected long getNodesInRequisition(final String foreignSource) {
        try {
            final HttpGet request = new HttpGet(BASE_URL + "opennms/rest/requisitions/" + URLEncoder.encode(foreignSource, "UTF-8"));
            final ResponseData rd = getRequest(request);
            LOG.debug("getNodesInRequisition: response={}", rd);

            if (rd.getStatus() == 404 || rd.getStatus() == -1 || rd.getResponseText() == null) {
                return 0;
            }

            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document document = builder.parse(new InputSource(new StringReader(rd.getResponseText())));
            final Element rootElement = document.getDocumentElement();
            long count = 0;
            final NodeList children = rootElement.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                final Node child = children.item(i);
                if ("node".equals(child.getNodeName())) {
                    count++;
                }
            }
            return count;
        } catch (final Exception e) {
            throw new OpenNMSTestException(e);
        }
    }

    @Deprecated
    protected long getNodesInRequisition(final WebElement element) {
        LOG.debug("getNodesInRequisition: element={}", element);
        try {
            final WebElement match = element.findElement(By.xpath("//span[@data-requisitionedNodes]"));
            final String nodes = match.getAttribute("data-requisitionedNodes");
            if (nodes != null) {
                final Long nodeCount = Long.valueOf(nodes);
                LOG.debug("{} requisitioned nodes found.", nodeCount);
                return nodeCount;
            }
        } catch (final NoSuchElementException e) {
        }
        LOG.debug("0 requisitioned nodes found.");
        return 0;
    }

    @Deprecated
    protected long getNodesInDatabase(final WebElement element) {
        LOG.debug("getNodesInDatabase: element={}", element);
        try {
            final WebElement match = element.findElement(By.xpath("//span[@data-databaseNodes]"));
            final String nodes = match.getAttribute("data-databaseNodes");
            if (nodes != null) {
                final Long nodeCount = Long.valueOf(nodes);
                LOG.debug("{} database nodes found.", nodeCount);
                return nodeCount;
            }
        } catch (final NoSuchElementException e) {
        }
        LOG.debug("0 database nodes found.");
        return 0;
    }

    protected void sendPost(final String urlFragment, final String body) throws ClientProtocolException, IOException, InterruptedException {
        sendPost(urlFragment, body, null);
    }

    protected void sendPost(final String urlFragment, final String body, final Integer expectedResponse) throws ClientProtocolException, IOException, InterruptedException {
        LOG.debug("sendPost: url={}, expectedResponse={}, body={}", urlFragment, expectedResponse, body);
        final HttpPost post = new HttpPost(BASE_URL + "opennms" + (urlFragment.startsWith("/")? urlFragment : "/" + urlFragment));
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_XML));
        final Integer response = doRequest(post);
        if (expectedResponse == null) {
            if (response == null || (response != 303 && response != 200 && response != 201 && response != 202)) {
                throw new RuntimeException("Bad response code! (" + response + "; expected 200, 201, 202, or 303)");
            }
        } else {
            if (!expectedResponse.equals(response)) {
                throw new RuntimeException("Bad response code! (" + response + "; expected " + expectedResponse + ")");
            }
        }
    }

    protected void sendDelete(final String urlFragment) throws ClientProtocolException, IOException, InterruptedException {
        sendDelete(urlFragment, null);
    }

    protected void sendDelete(final String urlFragment, final Integer expectedResponse) throws ClientProtocolException, IOException, InterruptedException {
        LOG.debug("sendDelete: url={}, expectedResponse={}", urlFragment, expectedResponse);
        final HttpDelete del = new HttpDelete(BASE_URL + "opennms" + (urlFragment.startsWith("/") ? urlFragment : "/" + urlFragment));
        final Integer response = doRequest(del);
        if (expectedResponse == null) {
            if (response == null || (response != 303 && response != 200 && response != 202 && response != 204)) {
                throw new RuntimeException("Bad response code! (" + response + "; expected 200, 202, 204, or 303)");
            }
        } else {
            if (!expectedResponse.equals(response)) {
                throw new RuntimeException("Bad response code! (" + response + "; expected " + expectedResponse + ")");
            }
        }
    }

    protected final class WaitForNodesInDatabase implements ExpectedCondition<Boolean> {
        private final String m_foreignSource;
        private final int m_numberToMatch;

        public WaitForNodesInDatabase(int numberOfNodes) {
            this(REQUISITION_NAME, numberOfNodes);
        }

        public WaitForNodesInDatabase(final String foreignSource, int numberOfNodes) {
            m_foreignSource = foreignSource;
            m_numberToMatch = numberOfNodes;
            LOG.debug("WaitForNodesInDatabase: foreignSource={}, expectedNodes={}", foreignSource, numberOfNodes);
        }

        @Override
        public Boolean apply(final WebDriver input) {
            try {
                final long nodes = getNodesInDatabase(m_foreignSource);
                LOG.debug("WaitForNodesInDatabase: foreignSource={}, count={}", m_foreignSource, nodes);
                if (nodes == m_numberToMatch) {
                    return true;
                }
            } catch (final Exception e) {
                LOG.warn("WaitForNodesInDatabase: foreignSource={}, count={}: Failed while attempting to validate.", m_foreignSource, m_numberToMatch, e);
            }
            return null;
        }
    }

    protected final class WaitForNodesInRequisition implements ExpectedCondition<Boolean> {
        private final String m_foreignSource;
        private final int m_numberToMatch;

        public WaitForNodesInRequisition(final String foreignSource, int numberOfNodes) {
            m_foreignSource = foreignSource;
            m_numberToMatch = numberOfNodes;
            LOG.debug("WaitForNodesInRequisition: foreignSource={}, expectedNodes={}", foreignSource, numberOfNodes);
        }

        public WaitForNodesInRequisition(int numberOfNodes) {
            m_foreignSource = REQUISITION_NAME;
            m_numberToMatch = numberOfNodes;
            LOG.debug("WaitForNodesInRequisition: foreignSource={}, expectedNodes={}", m_foreignSource, numberOfNodes);
        }

        @Override
        public Boolean apply(final WebDriver input) {
            try {
                final long nodes = getNodesInRequisition(m_foreignSource);
                LOG.debug("WaitForNodesInRequisition: foreignSource={}, count={}", m_foreignSource, nodes);
                if (nodes == m_numberToMatch) {
                    return true;
                }
            } catch (final Exception e) {
                LOG.warn("WaitForNodesInRequisition: foreignSource={}, expectedNodes={}: Failed to get nodes in requisition {}.", m_foreignSource, m_numberToMatch, e);
            }
            return null;
        }
    }
}
