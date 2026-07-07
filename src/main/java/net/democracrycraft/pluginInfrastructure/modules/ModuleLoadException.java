package net.democracrycraft.pluginInfrastructure.modules;

public class ModuleLoadException extends Exception {

    public ModuleLoadException() {
        super();
    }

    public ModuleLoadException(String message) {
        super(message);
    }

    public ModuleLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModuleLoadException(Throwable cause) {
        super(cause);
    }

    protected ModuleLoadException(String message,
                                  Throwable cause,
                                  boolean enableSuppression,
                                  boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
