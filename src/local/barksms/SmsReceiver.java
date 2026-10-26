package local.barksms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public final class SmsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())
                || !new Settings(context).enabled() || !Settings.hasPermission(context)) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) {
            if (part != null && part.getMessageBody() != null) body.append(part.getMessageBody());
        }
        if (body.length() == 0) return;
        String sender = parts[0].getDisplayOriginatingAddress();
        if (sender == null) sender = "未知号码";
        QueueDb db = QueueDb.get(context);
        if (db.enqueue(sender, body.toString(), parts[0].getTimestampMillis())) {
            RetryJob.schedule(context, 1000);
            ForwardService.start(context, false);
        }
    }
}
