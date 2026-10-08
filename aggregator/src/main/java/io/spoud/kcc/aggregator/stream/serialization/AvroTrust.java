package io.spoud.kcc.aggregator.stream.serialization;

import io.spoud.kcc.data.ContextData;
import org.apache.avro.util.ClassSecurityValidator;

/**
 * Avro 1.12.2 added ClassSecurityValidator, which by default refuses to resolve any class outside a
 * small trusted set when (de)serializing specific records - and also when a builder fills in a
 * default for a nested record, e.g. a pricing rule's earlier prices. Trust our own generated
 * package on top of the default rules rather than the whole classpath.
 * <p>
 * Every class that reads or builds our records calls {@link #ensure()} first, so the order classes
 * happen to load in doesn't matter.
 */
public final class AvroTrust {

    static {
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.composite(
                ClassSecurityValidator.DEFAULT,
                clazz -> ContextData.class.getPackageName().equals(clazz.getPackageName())));
    }

    private AvroTrust() {
    }

    /** Loads this class, which sets the trust once. */
    public static void ensure() {
        // the static initializer does the work
    }
}
