/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018-2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Properties;

import org.slf4j.Logger;

/**
 * Helper class to setup logging for smoke tests
 */
public abstract class LoggingUtils {

    private static final String APACHE_LOG_LEVEL = "INFO"; // change this to help debug smoke tests

    public static void setupLoggingForSeleniumTests() {
        final String logLevel = System.getProperty("org.opennms.smoketest.logLevel", "DEBUG");

        /* Set up the mock log appender, if it is in the classpath */
        final Properties props = new Properties();
        props.put("log4j.logger.org.apache.cxf", APACHE_LOG_LEVEL);
        props.put("log4j.logger.org.apache.cxf.phase.PhaseInterceptorChain", "ERROR");
        props.put("log4j.logger.org.apache.http", APACHE_LOG_LEVEL);
        try {
            final Class<?> mockLogAppender = Class.forName("org.opennms.core.test.MockLogAppender");
            if (mockLogAppender != null) {
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
        System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.cxf", APACHE_LOG_LEVEL);
        System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.cxf.phase.PhaseInterceptorChain", "ERROR");
        System.setProperty("org.apache.commons.logging.simplelog.log.org.apache.http", APACHE_LOG_LEVEL);

        /* Set up logback, if it's there */
        setLevel(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME, logLevel);
        setLevel("org.apache.cxf", APACHE_LOG_LEVEL);
        setLevel("org.apache.cxf.phase.PhaseInterceptorChain", "ERROR");
        setLevel("org.apache.http", APACHE_LOG_LEVEL);
    }

    private static final boolean setLevel(final String pack, final String level) {
        final Logger logger = org.slf4j.LoggerFactory.getLogger(pack);
        if (logger instanceof ch.qos.logback.classic.Logger) {
            ((ch.qos.logback.classic.Logger) logger).setLevel(ch.qos.logback.classic.Level.valueOf(level));
            return true;
        }
        return false;
    }
}
