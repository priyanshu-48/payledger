package com.payledger.security.testing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;

/**
 * Collects what the application logs while it is open so a test can assert that secrets never appear: message
 * text, arguments and the text of exceptions with their causes. Our own code and the framework loggers that
 * log requests (Spring Security, DispatcherServlet) are raised to DEBUG, which is stricter than production.
 * Deliberately NOT raised: client libraries (the test's HTTP client runs in the same JVM and would log the very
 * headers the test sends), Spring's exception resolvers (their DEBUG line repeats exception messages, which for
 * invalid input contain the rejected value) and the MVC body processors (they print every decoded request and
 * response object). Production runs at INFO and DEBUG on org.springframework.web must never be enabled there;
 * secret-carrying DTOs additionally mask themselves in toString (tested where they are defined).
 */
public final class LogCapture implements AutoCloseable {

    private static final List<String> WATCHED = List.of("com.payledger", "org.springframework.security",
            "org.springframework.web.servlet.DispatcherServlet");

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> raised = new ArrayList<>();
    private final List<Level> previous = new ArrayList<>();

    public LogCapture() {
        for (String name : WATCHED) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            raised.add(logger);
            previous.add(logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
        appender.start();
        root.addAppender(appender);
    }

    /** Every captured line as one string (formatted message plus exception chain). */
    public String everything() {
        StringBuilder text = new StringBuilder();
        List<ILoggingEvent> events = List.copyOf(appender.list);
        for (ILoggingEvent event : events) {
            text.append(event.getLoggerName()).append(' ').append(event.getFormattedMessage()).append('\n');
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                text.append(t.getClassName()).append(": ").append(t.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    public int size() {
        return appender.list.size();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        appender.stop();
        for (int i = 0; i < raised.size(); i++) {
            raised.get(i).setLevel(previous.get(i));
        }
    }
}
