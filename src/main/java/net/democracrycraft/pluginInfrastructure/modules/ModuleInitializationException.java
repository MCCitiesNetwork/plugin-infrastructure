package net.democracrycraft.pluginInfrastructure.modules;

public class ModuleInitializationException extends Exception {

    public ModuleInitializationException() {
        super();
    }

    public ModuleInitializationException(String message) {
        super(message);
    }

    public ModuleInitializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModuleInitializationException(Throwable cause) {
        super(cause);
    }

    protected ModuleInitializationException(String message,
                                            Throwable cause,
                                            boolean enableSuppression,
                                            boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
