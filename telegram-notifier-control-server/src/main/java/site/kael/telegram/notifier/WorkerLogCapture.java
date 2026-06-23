package site.kael.telegram.notifier;

import org.springframework.stereotype.Component;
import site.kael.telegram.python.PythonSubprocessTelegramAccountSessionManager;
import site.kael.telegram.starter.TelegramAccountSessionManager;

/**
 * 订阅 Python worker 日志并持久化到 account_worker_logs 表。
 */
@Component
class WorkerLogCapture {
    WorkerLogCapture(TelegramAccountSessionManager sessions, AccountWorkerLogService workerLogService) {
        if (sessions instanceof PythonSubprocessTelegramAccountSessionManager manager) {
            manager.subscribeLogs((accountId, message) -> {
                try {
                    workerLogService.capture(accountId, message);
                } catch (Exception ignored) {
                    // best-effort, avoid log capture failure affecting the worker
                }
            });
        }
    }
}
