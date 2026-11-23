package dev.fawpie.cherrymanager;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public final class StartupReceiver extends BroadcastReceiver {
    static final int JOB_ID = 39120;

    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                !CherryData.settings(context).getBoolean(CherryData.STARTUP_ENABLED, false)) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, StartupProfileJob.class))
                .setMinimumLatency(4000)
                .setOverrideDeadline(45000)
                .build();
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            CherryData.log(context, "Startup profile", "Android could not schedule the profile.");
        }
    }
}
