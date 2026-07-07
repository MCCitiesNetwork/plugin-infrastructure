package net.democracrycraft.pluginInfrastructure.modules;

public class ModuleShutdownException extends Exception {
    public ModuleShutdownException() {
        super();
    }

    public ModuleShutdownException(String message) {
        super(message);
    }

    public ModuleShutdownException(String message, Throwable cause) {
        super(message, cause);
    }

    public ModuleShutdownException(Throwable cause) {
        super(cause);
    }

    protected ModuleShutdownException(String message,
                                      Throwable cause,
                                      boolean enableSuppression,
                                      boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
