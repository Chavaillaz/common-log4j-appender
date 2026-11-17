/**
 * An appender built on the library: it requires its module, which brings the Log4j2 API along, and opens the
 * package of its plugin to Log4j2, which builds the plugin by reflection.
 */
module com.example.appender {

    requires com.chavaillaz.appender;

    opens com.example.appender to org.apache.logging.log4j.core;

}
