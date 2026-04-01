// SPDX-License-Identifier: BSD-3-Clause
module org.github.crac.systemd_appender {
    requires org.apache.logging.log4j.core;
    requires org.crac;

    // Log4j instantiates plugin builders reflectively; open unconditionally
    // because log4j-core is typically on the classpath (unnamed module), and
    // named-to-named qualified opens are only possible when both sides are on
    // the module path.
    opens org.github.crac.systemd_appender;
}
