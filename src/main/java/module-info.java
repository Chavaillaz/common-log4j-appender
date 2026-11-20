/**
 * Building blocks of Log4j2 appenders shipping logs to an external system by batches, off the application
 * thread, with bounded memory, retries and a bounded shutdown.
 * <ul>
 *     <li>{@link com.chavaillaz.appender}: the contracts of a delivery, with its options, listeners and
 *     metrics</li>
 *     <li>{@link com.chavaillaz.appender.log4j}: the appender, the batching delivery and the conversion an
 *     appender extends</li>
 * </ul>
 * The batching engine behind the delivery lives in {@code com.chavaillaz.appender.internal}, which is not
 * exported.
 * The Log4j2 and JSpecify APIs are required transitively, the API of this module exposing their types. An
 * appender built on it is a plugin Log4j2 creates by reflection, so the module declaring it opens its package to
 * {@code org.apache.logging.log4j.core}, as this module opens the package of the builder every plugin extends.
 */
module com.chavaillaz.appender {

    requires transitive org.apache.logging.log4j;
    requires transitive org.apache.logging.log4j.core;
    requires transitive org.jspecify;

    exports com.chavaillaz.appender;
    exports com.chavaillaz.appender.log4j;
    // The builder every appender plugin extends (see LogDeliveryAppenderBuilder), whose fields Log4j2
    // sets by reflection, as it sets those of the plugin itself
    opens com.chavaillaz.appender.log4j to org.apache.logging.log4j.core;

}
