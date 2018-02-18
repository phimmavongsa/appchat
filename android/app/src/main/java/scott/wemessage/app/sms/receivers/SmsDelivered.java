package scott.wemessage.app.sms.receivers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.content.LocalBroadcastManager;

import com.klinker.android.send_message.DeliveredReceiver;

import scott.wemessage.app.AppLogger;
import scott.wemessage.app.models.sms.messages.MmsMessage;
import scott.wemessage.app.weMessage;

public class SmsDelivered extends DeliveredReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        try {
            Uri messageUri = Uri.parse(intent.getStringExtra("message_uri"));
            MmsMessage message = weMessage.get().getMmsDatabase().getMessageFromUri(messageUri);

            if (message == null) return;
            weMessage.get().getMmsManager().updateOrAddMessage(intent.getStringExtra("task_identifier"), message);
        }catch (Exception ex){
            AppLogger.error("An error occurred while updating an SMS message", ex);
            LocalBroadcastManager.getInstance(weMessage.get()).sendBroadcast(new Intent(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR));
        }
    }
}