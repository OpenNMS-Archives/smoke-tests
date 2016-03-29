package org.opennms.smoketest;

public class OpenNMSTestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OpenNMSTestException() {
        super();
    }
    
    public OpenNMSTestException(final String message) {
        super(message);
    }
    
    public OpenNMSTestException(final Throwable cause) {
        super(cause);
    }
    
    public OpenNMSTestException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
