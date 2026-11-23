package dev.fawpie.cherrymanager;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;

public final class StartupProfileJob extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Thread worker;

    @Override public boolean onStartJob(JobParameters params) {
        if (!CherryData.settings(this).getBoolean(CherryData.STARTUP_ENABLED, false)) return false;
        worker = new Thread(() -> apply(params), "Cherry startup profile");
        worker.start();
        return true;
    }

    private void apply(JobParameters params) {
        Thread operation = Thread.currentThread();
        String profile = CherryData.startupProfile(this);
        try {
            if (KernelClient.get(this).applyStartupProfile(this, profile)) {
                CherryData.log(this, "Startup profile", CherryData.profileName(profile) + " applied and verified.");
            }
        } catch (IOException failure) {
            CherryData.log(this, "Startup profile", failure.getMessage() == null
                    ? "The startup profile failed." : failure.getMessage());
        } catch (InterruptedException interrupted) {
            operation.interrupt();
        } finally {
            main.post(() -> {
                if (worker != operation) return;
                worker = null;
                jobFinished(params, false);
            });
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        worker = null;
        return false;
    }
}
