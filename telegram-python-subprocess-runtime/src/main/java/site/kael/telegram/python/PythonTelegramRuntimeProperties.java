package site.kael.telegram.python;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "telegram.client.python")
public class PythonTelegramRuntimeProperties {
    private String executable = "python3";
    private Path workerScript;
    private Path workingDirectory;
    private List<String> extraArgs = new ArrayList<>();
    private List<String> requiredModules = new ArrayList<>(List.of("pyrogram", "tgcrypto"));

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable == null || executable.isBlank() ? "python3" : executable;
    }

    public Path getWorkerScript() {
        return workerScript;
    }

    public void setWorkerScript(Path workerScript) {
        this.workerScript = workerScript;
    }

    public Path getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public List<String> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs == null ? new ArrayList<>() : extraArgs;
    }

    public List<String> getRequiredModules() {
        return requiredModules;
    }

    public void setRequiredModules(List<String> requiredModules) {
        this.requiredModules = requiredModules == null ? new ArrayList<>() : requiredModules;
    }
}
