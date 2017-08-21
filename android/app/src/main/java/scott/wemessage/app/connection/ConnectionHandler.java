package scott.wemessage.app.connection;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import scott.wemessage.R;
import scott.wemessage.app.AppLogger;
import scott.wemessage.app.messages.MessageDatabase;
import scott.wemessage.app.messages.MessageManager;
import scott.wemessage.app.messages.objects.Account;
import scott.wemessage.app.messages.objects.Attachment;
import scott.wemessage.app.messages.objects.Contact;
import scott.wemessage.app.messages.objects.Handle;
import scott.wemessage.app.messages.objects.Message;
import scott.wemessage.app.messages.objects.chats.Chat;
import scott.wemessage.app.messages.objects.chats.GroupChat;
import scott.wemessage.app.messages.objects.chats.PeerChat;
import scott.wemessage.app.security.CryptoFile;
import scott.wemessage.app.security.CryptoType;
import scott.wemessage.app.security.DecryptionTask;
import scott.wemessage.app.security.EncryptionTask;
import scott.wemessage.app.security.FileDecryptionTask;
import scott.wemessage.app.security.KeyTextPair;
import scott.wemessage.app.security.util.AndroidBase64Wrapper;
import scott.wemessage.app.utils.FileLocationContainer;
import scott.wemessage.app.weMessage;
import scott.wemessage.commons.json.action.JSONAction;
import scott.wemessage.commons.json.action.JSONResult;
import scott.wemessage.commons.json.connection.ClientMessage;
import scott.wemessage.commons.json.connection.ConnectionMessage;
import scott.wemessage.commons.json.connection.InitConnect;
import scott.wemessage.commons.json.connection.ServerMessage;
import scott.wemessage.commons.json.message.JSONAttachment;
import scott.wemessage.commons.json.message.JSONChat;
import scott.wemessage.commons.json.message.JSONMessage;
import scott.wemessage.commons.json.message.security.JSONEncryptedFile;
import scott.wemessage.commons.json.message.security.JSONEncryptedText;
import scott.wemessage.commons.types.ActionType;
import scott.wemessage.commons.types.DeviceType;
import scott.wemessage.commons.types.DisconnectReason;
import scott.wemessage.commons.types.ReturnType;
import scott.wemessage.commons.utils.ByteArrayAdapter;
import scott.wemessage.commons.utils.DateUtils;
import scott.wemessage.commons.utils.StringUtils;

public final class ConnectionHandler extends Thread {

    private final String TAG = ConnectionService.TAG;
    private final int UPDATE_MESSAGES_ATTEMPT_QUEUE = 20;
    private final int TIME_TO_CONNECT = 2; //TODO: Work

    private final Object serviceLock = new Object();
    private final Object socketLock = new Object();
    private final Object inputStreamLock = new Object();
    private final Object outputStreamLock = new Object();

    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean hasTriedAuthenticating = new AtomicBoolean(false);
    private AtomicBoolean isConnected = new AtomicBoolean(false);

    private ConcurrentHashMap<String, ConnectionMessage>connectionMessagesMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, String>messageAndConnectionMessageMap = new ConcurrentHashMap<>();

    private ConnectionService service;
    private Socket connectionSocket;
    private ObjectInputStream inputStream;
    private ObjectOutputStream outputStream;

    private final String ipAddress;
    private final int port;
    private String emailPlainText;
    private String passwordPlainText, passwordHashedText;

    protected ConnectionHandler(ConnectionService service, String ipAddress, int port, String emailPlainText, String password, boolean alreadyHashed){
        this.service = service;
        this.ipAddress = ipAddress;
        this.port = port;
        this.emailPlainText = emailPlainText;

        if (alreadyHashed){
            this.passwordHashedText = password;
        }else {
            this.passwordPlainText = password;
        }
    }

    public AtomicBoolean isRunning(){
        return isRunning;
    }

    public AtomicBoolean isConnected(){
        return isConnected;
    }

    private ConnectionService getParentService(){
        synchronized (serviceLock){
            return service;
        }
    }

    public Socket getConnectionSocket(){
        synchronized (socketLock){
            return connectionSocket;
        }
    }

    private ObjectInputStream getInputStream(){
        synchronized (inputStreamLock){
            return inputStream;
        }
    }

    private ObjectOutputStream getOutputStream(){
        synchronized (outputStreamLock){
            return outputStream;
        }
    }

    private ServerMessage getIncomingMessage(String prefix, Object incomingStream){
        String data = ((String) incomingStream).split(prefix)[1];
        ServerMessage serverMessage = new GsonBuilder().registerTypeHierarchyAdapter(byte[].class, new ByteArrayAdapter(new AndroidBase64Wrapper())).create().fromJson(data, ServerMessage.class);

        connectionMessagesMap.put(serverMessage.getMessageUuid(), serverMessage);
        return serverMessage;
    }

    private void sendOutgoingMessage(String prefix, Object outgoingData, Class<?> dataClass) throws IOException {
        Type type = TypeToken.get(dataClass).getType();
        String outgoingDataJson = new GsonBuilder().registerTypeHierarchyAdapter(byte[].class, new ByteArrayAdapter(new AndroidBase64Wrapper())).create().toJson(outgoingData, type);
        ClientMessage clientMessage = new ClientMessage(UUID.randomUUID().toString(), outgoingDataJson);
        String outgoingJson = new Gson().toJson(clientMessage);

        getOutputStream().writeObject(prefix + outgoingJson);
        getOutputStream().flush();

        connectionMessagesMap.put(clientMessage.getMessageUuid(), clientMessage);
    }

    //TODO: If not registered with imessage, use sms factory to send message see if that works if not add message failure etc.

    public void sendOutgoingMessage(final Message message, final boolean performMessageManagerAdd){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Type type = TypeToken.get(JSONMessage.class).getType();
                    String clientMessageUuid = UUID.randomUUID().toString();
                    String outgoingDataJson = new GsonBuilder().registerTypeHierarchyAdapter(byte[].class, new ByteArrayAdapter(new AndroidBase64Wrapper())).create().toJson(message.toJson(), type);
                    ClientMessage clientMessage = new ClientMessage(clientMessageUuid, outgoingDataJson);
                    String outgoingJson = new Gson().toJson(clientMessage);

                    connectionMessagesMap.put(clientMessage.getMessageUuid(), clientMessage);
                    messageAndConnectionMessageMap.put(clientMessageUuid, message.getUuid().toString());

                    if (performMessageManagerAdd) {
                        weMessage.get().getMessageManager().addMessage(message, false);
                    }

                    getOutputStream().writeObject(weMessage.JSON_NEW_MESSAGE + outgoingJson);
                    getOutputStream().flush();
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_SEND_MESSAGE_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to send a new message", ex);
                }
            }
        }).start();
    }

    private void sendOutgoingGenericAction(final ActionType actionType, final String... args){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONAction jsonAction;

                    switch (actionType) {
                        case ADD_PARTICIPANT:
                            jsonAction = new JSONAction(actionType.getCode(), new String[]{args[0], args[1], args[2], args[3]});
                            break;
                        case CREATE_GROUP:
                            jsonAction = new JSONAction(actionType.getCode(), new String[]{args[0], args[1], args[2]});
                            break;
                        case LEAVE_GROUP:
                            jsonAction = new JSONAction(actionType.getCode(), new String[]{args[0], args[1], args[2]});
                            break;
                        case REMOVE_PARTICIPANT:
                            jsonAction = new JSONAction(actionType.getCode(), new String[]{args[0], args[1], args[2], args[3]});
                            break;
                        case RENAME_GROUP:
                            jsonAction = new JSONAction(actionType.getCode(), new String[]{args[0], args[1], args[2], args[3]});
                            break;
                        default:
                            sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                            AppLogger.error(TAG, "Could not perform action due to an unknown Action Type", new NullPointerException());
                            return;
                    }

                    sendOutgoingMessage(weMessage.JSON_ACTION, jsonAction, JSONAction.class);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to send an action to be performed", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingAddParticipantAction(final GroupChat chat, final String participant){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Message lastMessage = weMessage.get().getMessageDatabase().getLastMessageFromChat(chat);
                    Date lastMessageDate = lastMessage.getModernDateSent();
                    String timeArgument;
                    String lastMessageText;

                    if (lastMessage.getText() == null){
                        lastMessageText = "";
                    }else {
                        lastMessageText = lastMessage.getText();
                    }

                    if (DateUtils.isSameDay(Calendar.getInstance().getTime(), lastMessageDate)) {
                        timeArgument = new SimpleDateFormat("hh:mm a").format(lastMessageDate);
                    } else {
                        if (DateUtils.wasDateYesterday(lastMessageDate, Calendar.getInstance().getTime())) {
                            timeArgument = getParentService().getString(R.string.word_yesterday);
                        } else {
                            timeArgument = new SimpleDateFormat("M/d/yy").format(lastMessageDate);
                        }
                    }
                    sendOutgoingGenericAction(ActionType.ADD_PARTICIPANT, chat.getUIDisplayName(true), timeArgument, lastMessageText, participant);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to perform the AddParticipant action", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingRemoveParticipantAction(final GroupChat chat, final String participant){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Message lastMessage = weMessage.get().getMessageDatabase().getLastMessageFromChat(chat);
                    Date lastMessageDate = lastMessage.getModernDateSent();
                    String timeArgument;
                    String lastMessageText;

                    if (lastMessage.getText() == null){
                        lastMessageText = "";
                    }else {
                        lastMessageText = lastMessage.getText();
                    }

                    if (DateUtils.isSameDay(Calendar.getInstance().getTime(), lastMessageDate)) {
                        timeArgument = new SimpleDateFormat("hh:mm a").format(lastMessageDate);
                    } else {
                        if (DateUtils.wasDateYesterday(lastMessageDate, Calendar.getInstance().getTime())) {
                            timeArgument = getParentService().getString(R.string.word_yesterday);
                        } else {
                            timeArgument = new SimpleDateFormat("M/d/yy").format(lastMessageDate);
                        }
                    }
                    sendOutgoingGenericAction(ActionType.REMOVE_PARTICIPANT, chat.getUIDisplayName(true), timeArgument, lastMessageText, participant);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to perform the RemoveParticipant action", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingRenameGroupAction(final GroupChat chat, final String newTitle){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Message lastMessage = weMessage.get().getMessageDatabase().getLastMessageFromChat(chat);
                    Date lastMessageDate = lastMessage.getModernDateSent();
                    String timeArgument;
                    String lastMessageText;

                    if (lastMessage.getText() == null){
                        lastMessageText = "";
                    }else {
                        lastMessageText = lastMessage.getText();
                    }

                    if (DateUtils.isSameDay(Calendar.getInstance().getTime(), lastMessageDate)) {
                        timeArgument = new SimpleDateFormat("hh:mm a").format(lastMessageDate);
                    } else {
                        if (DateUtils.wasDateYesterday(lastMessageDate, Calendar.getInstance().getTime())) {
                            timeArgument = getParentService().getString(R.string.word_yesterday);
                        } else {
                            timeArgument = new SimpleDateFormat("M/d/yy").format(lastMessageDate);
                        }
                    }
                    sendOutgoingGenericAction(ActionType.RENAME_GROUP, chat.getUIDisplayName(true), timeArgument, lastMessageText, newTitle);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to perform the RenameGroup action", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingCreateGroupAction(final String groupName, final List<String>participants, final String initMessage){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500);
                    sendOutgoingGenericAction(ActionType.CREATE_GROUP, groupName, StringUtils.join(participants, ",", 1), initMessage);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to perform the CreateGroup action", ex);
                }
            }
        }).start();
    }

    public void sendOutgoingLeaveGroupAction(final GroupChat chat){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Message lastMessage = weMessage.get().getMessageDatabase().getLastMessageFromChat(chat);
                    Date lastMessageDate = lastMessage.getModernDateSent();
                    String timeArgument;
                    String lastMessageText;

                    if (lastMessage.getText() == null){
                        lastMessageText = "";
                    }else {
                        lastMessageText = lastMessage.getText();
                    }

                    if (DateUtils.isSameDay(Calendar.getInstance().getTime(), lastMessageDate)) {
                        timeArgument = new SimpleDateFormat("hh:mm a").format(lastMessageDate);
                    } else {
                        if (DateUtils.wasDateYesterday(lastMessageDate, Calendar.getInstance().getTime())) {
                            timeArgument = getParentService().getString(R.string.word_yesterday);
                        } else {
                            timeArgument = new SimpleDateFormat("M/d/yy").format(lastMessageDate);
                        }
                    }
                    sendOutgoingGenericAction(ActionType.LEAVE_GROUP, chat.getUIDisplayName(true), timeArgument, lastMessageText);
                }catch(Exception ex){
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                    AppLogger.error(TAG, "An error occurred while trying to perform the LeaveGroup action", ex);
                }
            }
        }).start();
    }

    public void run(){
        final ByteArrayAdapter byteArrayAdapter = new ByteArrayAdapter(new AndroidBase64Wrapper());

        isRunning.set(true);

        synchronized (socketLock) {
            connectionSocket = new Socket();
        }

        try {
            Thread.sleep(TIME_TO_CONNECT * 1000);
        }catch(Exception ex){
            AppLogger.error(TAG, "An error occurred while trying to make a thread sleep", ex);
        }

        try {
            getConnectionSocket().connect(new InetSocketAddress(ipAddress, port), weMessage.CONNECTION_TIMEOUT_WAIT * 1000);

            synchronized (outputStreamLock) {
                outputStream = new ObjectOutputStream(getConnectionSocket().getOutputStream());
            }

            synchronized (inputStreamLock) {
                inputStream = new ObjectInputStream(getConnectionSocket().getInputStream());
            }
        }catch(SocketTimeoutException ex){
            if (isRunning.get()) {
                sendLocalBroadcast(weMessage.BROADCAST_LOGIN_TIMEOUT, null);
                getParentService().endService();
            }
            return;
        }catch(IOException ex){
            if (isRunning.get()) {
                AppLogger.error(TAG, "An error occurred while connecting to the weServer.", ex);
                sendLocalBroadcast(weMessage.BROADCAST_LOGIN_ERROR, null);
                getParentService().endService();
            }
            return;
        }

        while (isRunning.get() && !hasTriedAuthenticating.get()){
            try {
                String incoming = (String) getInputStream().readObject();
                if (incoming.startsWith(weMessage.JSON_VERIFY_PASSWORD_SECRET)){
                    ServerMessage message = getIncomingMessage(weMessage.JSON_VERIFY_PASSWORD_SECRET, incoming);
                    JSONEncryptedText secretEncrypted = (JSONEncryptedText) message.getOutgoing(JSONEncryptedText.class, byteArrayAdapter);

                    DecryptionTask secretDecryptionTask = new DecryptionTask(new KeyTextPair(secretEncrypted.getEncryptedText(), secretEncrypted.getKey()), CryptoType.AES);
                    EncryptionTask emailEncryptionTask = new EncryptionTask(emailPlainText, null, CryptoType.AES);

                    secretDecryptionTask.runDecryptTask();
                    emailEncryptionTask.runEncryptTask();

                    String secretString = secretDecryptionTask.getDecryptedText();

                    String hashedPass;

                    if (passwordPlainText == null){
                        hashedPass = passwordHashedText;
                    }else {
                        EncryptionTask hashPasswordTask = new EncryptionTask(passwordPlainText, secretString, CryptoType.BCRYPT);
                        hashPasswordTask.runEncryptTask();

                        hashedPass = hashPasswordTask.getEncryptedText().getEncryptedText();
                        this.passwordHashedText = hashedPass;
                    }

                    EncryptionTask encryptHashedPasswordTask = new EncryptionTask(hashedPass, null, CryptoType.AES);
                    encryptHashedPasswordTask.runEncryptTask();

                    KeyTextPair encryptedEmail = emailEncryptionTask.getEncryptedText();
                    KeyTextPair encryptedHashedPassword = encryptHashedPasswordTask.getEncryptedText();

                    InitConnect initConnect = new InitConnect(
                            weMessage.WEMESSAGE_BUILD_VERSION,
                            Settings.Secure.getString(getParentService().getContentResolver(), Settings.Secure.ANDROID_ID),
                            KeyTextPair.toEncryptedJSON(encryptedEmail),
                            KeyTextPair.toEncryptedJSON(encryptedHashedPassword),
                            DeviceType.ANDROID.getTypeName()
                    );

                    sendOutgoingMessage(weMessage.JSON_INIT_CONNECT, initConnect, InitConnect.class);
                    hasTriedAuthenticating.set(true);
                }
            }catch(Exception ex){
                AppLogger.error(TAG, "An error occurred while authenticating login information", ex);

                Bundle extras = new Bundle();
                extras.putString(weMessage.BUNDLE_DISCONNECT_REASON_ALTERNATE_MESSAGE, getParentService().getString(R.string.connection_error_authentication_message));
                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_ERROR, extras);
                getParentService().endService();
                return;
            }
        }

        while (isRunning.get()){
            try {
                MessageDatabase database = weMessage.get().getMessageDatabase();
                final String incoming = (String) getInputStream().readObject();

                if (incoming.startsWith(weMessage.JSON_SUCCESSFUL_CONNECTION)){
                    isConnected.set(true);
                    Account currentAccount = new Account().setEmail(emailPlainText).setEncryptedPassword(passwordHashedText);

                    if (database.getAccountByEmail(emailPlainText) == null){
                        currentAccount.setUuid(UUID.randomUUID());

                        weMessage.get().setCurrentAccount(currentAccount);
                        database.addAccount(currentAccount);

                        Handle meHandle = database.getHandleByAccount(weMessage.get().getCurrentAccount());

                        if (database.getContactByHandle(meHandle) == null) {
                            weMessage.get().getMessageManager().addContact(new Contact(UUID.randomUUID(), null, null, meHandle, null), false);
                        }
                    }else {
                        UUID oldUUID = database.getAccountByEmail(emailPlainText).getUuid();
                        currentAccount.setUuid(oldUUID);

                        weMessage.get().setCurrentAccount(currentAccount);
                        database.updateAccount(oldUUID.toString(), currentAccount);
                    }

                    String hostToSave;

                    if (port == weMessage.DEFAULT_PORT){
                        hostToSave = ipAddress;
                    }else {
                        hostToSave = ipAddress + ":" + port;
                    }

                    SharedPreferences.Editor editor = getParentService().getSharedPreferences(weMessage.APP_IDENTIFIER, Context.MODE_PRIVATE).edit();
                    editor.putString(weMessage.SHARED_PREFERENCES_LAST_HOST, hostToSave);
                    editor.putString(weMessage.SHARED_PREFERENCES_LAST_EMAIL, emailPlainText);
                    editor.putString(weMessage.SHARED_PREFERENCES_LAST_HASHED_PASSWORD, passwordHashedText);

                    editor.apply();

                    sendLocalBroadcast(weMessage.BROADCAST_LOGIN_SUCCESSFUL, null);
                } else if (incoming.startsWith(weMessage.JSON_CONNECTION_TERMINATED)) {
                    ServerMessage serverMessage = getIncomingMessage(weMessage.JSON_CONNECTION_TERMINATED, incoming);
                    DisconnectReason disconnectReason = DisconnectReason.fromCode(((Integer) serverMessage.getOutgoing(Integer.class, byteArrayAdapter)));

                    if (disconnectReason == null) {
                        AppLogger.error(TAG, "A null disconnect reason has caused the connection to be dropped", new NullPointerException());
                        Bundle extras = new Bundle();
                        extras.putString(weMessage.BUNDLE_DISCONNECT_REASON_ALTERNATE_MESSAGE, getParentService().getString(R.string.connection_error_unknown_message));
                        sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_ERROR, extras);
                        getParentService().endService();
                    } else {
                        switch (disconnectReason) {
                            case ALREADY_CONNECTED:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_ALREADY_CONNECTED, null);
                                getParentService().endService();
                                break;
                            case INVALID_LOGIN:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_INVALID_LOGIN, null);
                                getParentService().endService();
                                break;
                            case SERVER_CLOSED:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED, null);
                                getParentService().endService();
                                break;
                            case ERROR:
                                Bundle extras = new Bundle();
                                extras.putString(weMessage.BUNDLE_DISCONNECT_REASON_ALTERNATE_MESSAGE, getParentService().getString(R.string.connection_error_server_side_message));
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_ERROR, extras);
                                getParentService().endService();
                                break;
                            case FORCED:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_FORCED, null);
                                getParentService().endService();
                                break;
                            case CLIENT_DISCONNECTED:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_CLIENT_DISCONNECTED, null);
                                getParentService().endService();
                                break;
                            case INCORRECT_VERSION:
                                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_INCORRECT_VERSION, null);
                                getParentService().endService();
                                break;
                        }
                    }
                }else if (incoming.startsWith(weMessage.JSON_NEW_MESSAGE)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONMessage jsonMessage = (JSONMessage) getIncomingMessage(weMessage.JSON_NEW_MESSAGE, incoming).getOutgoing(JSONMessage.class, byteArrayAdapter);

                                JSONEncryptedText encryptedText = jsonMessage.getEncryptedText();
                                DecryptionTask textDecryptionTask = new DecryptionTask(new KeyTextPair(encryptedText.getEncryptedText(), encryptedText.getKey()), CryptoType.AES);
                                textDecryptionTask.runDecryptTask();

                                MessageManager messageManager = weMessage.get().getMessageManager();
                                MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();

                                for (String s : jsonMessage.getChat().getParticipants()) {
                                    if (messageDatabase.getHandleByHandleID(s) == null) {
                                        messageDatabase.addHandle(new Handle(UUID.randomUUID(), s, Handle.HandleType.IMESSAGE));
                                    }
                                }

                                for (String s : jsonMessage.getChat().getParticipants()) {
                                    if (messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)) == null) {
                                        messageManager.addContact(new Contact(UUID.randomUUID(), null, null, messageDatabase.getHandleByHandleID(s), null), false);
                                    }
                                }

                                JSONChat jsonChat = jsonMessage.getChat();
                                runChatCheck(messageManager, jsonChat, DateUtils.getDateUsing2001(jsonMessage.getDateSent() - 1));

                                messageManager.setHasUnreadMessages(messageDatabase.getChatByMacGuid(jsonChat.getMacGuid()), true, false);

                                Contact sender;

                                if (StringUtils.isEmpty(jsonMessage.getHandle())) {
                                    Handle meHandle = messageDatabase.getHandleByAccount(weMessage.get().getCurrentAccount());

                                    sender = messageDatabase.getContactByHandle(meHandle);
                                } else {
                                    sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(jsonMessage.getHandle()));
                                }


                                String attachmentNamePrefix = new SimpleDateFormat("HH-mm-ss_MM-dd-yyyy", Locale.US).format(Calendar.getInstance().getTime());
                                ArrayList<Attachment> attachments = new ArrayList<>();

                                for (JSONAttachment jsonAttachment : jsonMessage.getAttachments()) {
                                    Attachment attachment = new Attachment(UUID.randomUUID(), jsonAttachment.getMacGuid(), jsonAttachment.getTransferName(),
                                            new FileLocationContainer(
                                                    new File(weMessage.get().getAttachmentFolder(), attachmentNamePrefix + "-" + jsonAttachment.getTransferName())),
                                            jsonAttachment.getFileType(), jsonAttachment.getTotalBytes());

                                    JSONEncryptedFile jsonEncryptedFile = jsonAttachment.getFileData();
                                    FileDecryptionTask fileDecryptionTask = new FileDecryptionTask(new CryptoFile(jsonEncryptedFile.getEncryptedData(), jsonEncryptedFile.getKey(),
                                            jsonEncryptedFile.getIvParams()), CryptoType.AES);
                                    fileDecryptionTask.runDecryptTask();

                                    attachment.getFileLocation().writeBytesToFile(fileDecryptionTask.getDecryptedBytes());
                                    attachments.add(attachment);
                                }

                                Message message = new Message(UUID.randomUUID(), jsonMessage.getMacGuid(), messageDatabase.getChatByMacGuid(jsonChat.getMacGuid()), sender, attachments,
                                        textDecryptionTask.getDecryptedText(), jsonMessage.getDateSent(), jsonMessage.getDateDelivered(), jsonMessage.getDateRead(), jsonMessage.getErrored(),
                                        jsonMessage.isSent(), jsonMessage.isDelivered(), jsonMessage.isRead(), jsonMessage.isFinished(), jsonMessage.isFromMe());

                                messageManager.addMessage(message, false);
                            }catch(Exception ex){
                                sendLocalBroadcast(weMessage.BROADCAST_NEW_MESSAGE_ERROR, null);
                                AppLogger.error(TAG, "An error occurred while fetching a new message from the server", ex);
                            }
                        }
                    }).start();
                }else if (incoming.startsWith(weMessage.JSON_MESSAGE_UPDATED)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONMessage jsonMessage = (JSONMessage) getIncomingMessage(weMessage.JSON_MESSAGE_UPDATED, incoming).getOutgoing(JSONMessage.class, byteArrayAdapter);
                                MessageManager messageManager = weMessage.get().getMessageManager();
                                MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();

                                for (String s : jsonMessage.getChat().getParticipants()) {
                                    if (messageDatabase.getHandleByHandleID(s) == null) {
                                        messageDatabase.addHandle(new Handle(UUID.randomUUID(), s, Handle.HandleType.IMESSAGE));
                                    }
                                }

                                for (String s : jsonMessage.getChat().getParticipants()) {
                                    if (messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)) == null) {
                                        messageManager.addContact(new Contact(UUID.randomUUID(), null, null, messageDatabase.getHandleByHandleID(s), null), false);
                                    }
                                }

                                JSONChat jsonChat = jsonMessage.getChat();
                                runChatCheck(messageManager, jsonChat, DateUtils.getDateUsing2001(jsonMessage.getDateSent() - 1));

                                if (messageDatabase.getMessageByMacGuid(jsonMessage.getMacGuid()) == null){

                                    JSONEncryptedText encryptedText = jsonMessage.getEncryptedText();
                                    DecryptionTask textDecryptionTask = new DecryptionTask(new KeyTextPair(encryptedText.getEncryptedText(), encryptedText.getKey()), CryptoType.AES);
                                    textDecryptionTask.runDecryptTask();
                                    String decryptedText = textDecryptionTask.getDecryptedText();

                                    List<Message> messageCandidates = messageDatabase.getReversedMessagesWithSearchParameters(
                                            messageDatabase.getChatByMacGuid(jsonMessage.getChat().getMacGuid()), decryptedText, jsonMessage.isFromMe(), 0, UPDATE_MESSAGES_ATTEMPT_QUEUE);

                                    boolean updated = false;

                                    for (Message candidate : messageCandidates){
                                        if (candidate.getMacGuid() == null){
                                            updateMessage(messageManager, candidate, jsonMessage, true);
                                            updated = true;
                                            break;
                                        }
                                    }

                                    if (!updated) {
                                        if (!StringUtils.isEmpty(StringUtils.trimORC(decryptedText))) {
                                            if (jsonMessage.isFromMe()) {
                                                Contact sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByAccount(weMessage.get().getCurrentAccount()));

                                                Message message = new Message(UUID.randomUUID(), jsonMessage.getMacGuid(),
                                                        messageDatabase.getChatByMacGuid(jsonChat.getMacGuid()), sender, new ArrayList<Attachment>(),
                                                        textDecryptionTask.getDecryptedText(), jsonMessage.getDateSent(), jsonMessage.getDateDelivered(),
                                                        jsonMessage.getDateRead(), jsonMessage.getErrored(), jsonMessage.isSent(), jsonMessage.isDelivered(),
                                                        jsonMessage.isRead(), jsonMessage.isFinished(), jsonMessage.isFromMe());
                                                messageManager.addMessage(message, false);
                                            } else {
                                                sendLocalBroadcast(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR, null);
                                                AppLogger.log(AppLogger.Level.ERROR, TAG, "An error occurred while updating a message with Mac GUID: " + jsonMessage.getMacGuid() +
                                                        "  Reason: Previous message not found on system");
                                            }
                                        }
                                    }
                                }else {
                                    updateMessage(messageManager, messageDatabase.getMessageByMacGuid(jsonMessage.getMacGuid()), jsonMessage, false);
                                }
                            }catch(Exception ex){
                                sendLocalBroadcast(weMessage.BROADCAST_MESSAGE_UPDATE_ERROR, null);
                                AppLogger.error("An error occurred while updating the message", ex);
                            }
                        }
                    }).start();
                }else if (incoming.startsWith(weMessage.JSON_ACTION)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                MessageManager messageManager = weMessage.get().getMessageManager();
                                JSONAction jsonAction = (JSONAction) getIncomingMessage(weMessage.JSON_ACTION, incoming).getOutgoing(JSONAction.class, byteArrayAdapter);

                                performAction(messageManager, jsonAction);
                            }catch(Exception ex){
                                sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                                AppLogger.error("An error occurred while performing a JSONAction", ex);
                            }
                        }
                    }).start();
                }else if (incoming.startsWith(weMessage.JSON_RETURN_RESULT)){
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                MessageManager messageManager = weMessage.get().getMessageManager();
                                JSONResult jsonResult = (JSONResult) getIncomingMessage(weMessage.JSON_RETURN_RESULT, incoming).getOutgoing(JSONResult.class, byteArrayAdapter);

                                processResults(messageManager, jsonResult);
                            }catch(Exception ex){
                                sendLocalBroadcast(weMessage.BROADCAST_RESULT_PROCESS_ERROR, null);
                                AppLogger.error(TAG, "An error occurred while trying to process a return result", ex);
                            }
                        }
                    }).start();
                }
            }catch(EOFException ex){
                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_SERVER_CLOSED, null);
                getParentService().endService();
            }catch (Exception ex){
                Bundle extras = new Bundle();
                extras.putString(weMessage.BUNDLE_DISCONNECT_REASON_ALTERNATE_MESSAGE, getParentService().getString(R.string.connection_error_unknown_message));
                sendLocalBroadcast(weMessage.BROADCAST_DISCONNECT_REASON_ERROR, extras);
                AppLogger.error(TAG, "An unknown error occurred. Dropping connection to weServer", ex);
                getParentService().endService();
            }
        }
    }

    protected void endConnection(){
        if (isRunning.get()) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    isRunning.set(false);
                    try {
                        if (isConnected.get()) {
                            sendOutgoingMessage(weMessage.JSON_CONNECTION_TERMINATED, DisconnectReason.CLIENT_DISCONNECTED.getCode(), Integer.class);

                            isConnected.set(false);
                            getInputStream().close();
                            getOutputStream().close();
                        }
                        getConnectionSocket().close();
                        connectionMessagesMap.clear();
                        messageAndConnectionMessageMap.clear();

                        ConnectionHandler.this.interrupt();
                    } catch (Exception ex) {
                        AppLogger.error(TAG, "An error occurred while terminating the connection to the weServer.", ex);
                        ConnectionHandler.this.interrupt();
                    }
                }
            }).start();
        }
    }

    private void sendLocalBroadcast(String action, Bundle extras){
        Intent broadcastIntent = new Intent(action);

        if (extras != null) {
            broadcastIntent.putExtras(extras);
        }
        LocalBroadcastManager.getInstance(getParentService()).sendBroadcast(broadcastIntent);
    }

    private void newGroupChat(MessageManager messageManager, JSONChat jsonChat){
        ArrayList<Contact> contactList = new ArrayList<>();

        for (String s : jsonChat.getParticipants()) {
            contactList.add(weMessage.get().getMessageDatabase().getContactByHandle(weMessage.get().getMessageDatabase().getHandleByHandleID(s)));
        }
        GroupChat newChat = new GroupChat(UUID.randomUUID(), null, jsonChat.getMacGuid(), jsonChat.getMacGroupID(), jsonChat.getMacChatIdentifier(),
                true, true, jsonChat.getDisplayName(), contactList);

        messageManager.addChat(newChat, false);
    }

    private void updateGroupChat(MessageManager messageManager, GroupChat existingChat, JSONChat jsonChat, Date executionTime, boolean overrideAll){
        MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();

        ArrayList<String> existingChatParticipantList = new ArrayList<>();

        for (Contact c : existingChat.getParticipants()) {
            existingChatParticipantList.add(c.getHandle().getHandleID());
        }

        for (String s : existingChatParticipantList){
            if (!jsonChat.getParticipants().contains(s)){
                messageManager.removeParticipantFromGroup(existingChat, messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)), executionTime, false);
            }
        }

        for (String s : jsonChat.getParticipants()){
            if (!existingChatParticipantList.contains(s)){
                messageManager.addParticipantToGroup(existingChat, messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)), executionTime, false);
            }
        }

        if (!existingChat.getDisplayName().equals(jsonChat.getDisplayName())) {
            messageManager.renameGroupChat(existingChat, jsonChat.getDisplayName(), executionTime, false);
        }

        if (overrideAll){
            GroupChat updatedChat = (GroupChat) messageDatabase.getChatByUuid(existingChat.getUuid().toString());

            messageManager.updateChat(existingChat.getUuid().toString(), new GroupChat(existingChat.getUuid(), null, jsonChat.getMacGuid(), jsonChat.getMacGroupID(), jsonChat.getMacChatIdentifier(),
                    updatedChat.isInChat(), updatedChat.hasUnreadMessages(), updatedChat.getDisplayName(), updatedChat.getParticipants()), false);
        }
    }

    private void runChatCheck(MessageManager messageManager, JSONChat jsonChat, Date executionTime){
        MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();

        if (messageDatabase.getChatByMacGuid(jsonChat.getMacGuid()) == null) {
            if (jsonChat.getParticipants().size() < 2) {

                PeerChat peerChat = messageDatabase.getChatByHandle(messageDatabase.getHandleByHandleID(jsonChat.getParticipants().get(0)));
                if (peerChat != null){

                    PeerChat updatedChat = new PeerChat(peerChat.getUuid(), jsonChat.getMacGuid(), jsonChat.getMacGroupID(), jsonChat.getMacChatIdentifier(),
                            peerChat.isInChat(), peerChat.hasUnreadMessages(), peerChat.getContact());
                    messageManager.updateChat(peerChat.getUuid().toString(), updatedChat, false);

                }else {
                    PeerChat newChat = new PeerChat(UUID.randomUUID(), jsonChat.getMacGuid(), jsonChat.getMacGroupID(), jsonChat.getMacChatIdentifier(),
                            true, true, messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(jsonChat.getParticipants().get(0))));
                    messageManager.addChat(newChat, false);
                }

            } else {
                ArrayList<GroupChat> groupChats = new ArrayList<>(messageDatabase.getGroupChatsWithName(jsonChat.getDisplayName()));

                if (groupChats.size() == 0) {
                    newGroupChat(messageManager, jsonChat);

                } else if (groupChats.size() == 1) {

                    if (groupChats.get(0).getMacGuid() == null) {
                        updateGroupChat(messageManager, groupChats.get(0), jsonChat, executionTime, true);
                    } else {
                        newGroupChat(messageManager, jsonChat);
                    }
                } else {
                    boolean updated = false;

                    for (GroupChat groupChat : groupChats) {
                        if (groupChat.getMacGuid() == null) {
                            updateGroupChat(messageManager, groupChat, jsonChat, executionTime, true);
                            updated = true;
                            break;
                        }
                    }
                    if (!updated) {
                        newGroupChat(messageManager, jsonChat);
                    }
                }
            }
        } else {
            Chat existingChat = messageDatabase.getChatByMacGuid(jsonChat.getMacGuid());

            if (existingChat.getChatType() == Chat.ChatType.GROUP) {
                updateGroupChat(messageManager, (GroupChat) existingChat, jsonChat, executionTime, false);
            }
        }
    }

    private void processResults(MessageManager messageManager, JSONResult jsonResult){
        ByteArrayAdapter byteArrayAdapter = new ByteArrayAdapter(new AndroidBase64Wrapper());
        List<ReturnType> returnTypes = parseResults(jsonResult.getResult());
        ConnectionMessage connectionMessage = connectionMessagesMap.get(jsonResult.getCorrespondingUUID());

        if (connectionMessage == null){
            sendLocalBroadcast(weMessage.BROADCAST_RESULT_PROCESS_ERROR, null);
            AppLogger.error("Could not process result because the connection message was null", new NullPointerException());
            return;
        }

        if (connectionMessage instanceof ServerMessage){
            ServerMessage serverMessage = (ServerMessage) connectionMessage;
            boolean isAMessage;

            try {
                JSONMessage m = (JSONMessage) serverMessage.getOutgoing(JSONMessage.class, byteArrayAdapter);
                isAMessage = true;
            }catch (Exception ex){
                try {
                    JSONAction a = (JSONAction) serverMessage.getOutgoing(JSONAction.class, byteArrayAdapter);
                    isAMessage = false;
                }catch(Exception e){
                    sendLocalBroadcast(weMessage.BROADCAST_RESULT_PROCESS_ERROR, null);
                    AppLogger.error("Could not process result because an error occurred while parsing JSON", e);
                    return;
                }
            }

            if (isAMessage){
                JSONMessage jsonMessage = (JSONMessage) serverMessage.getOutgoing(JSONMessage.class, byteArrayAdapter);
                boolean isValid = (validateMessageReturnType(messageManager, jsonMessage, returnTypes.get(0)) && validateMessageReturnType(messageManager, jsonMessage, returnTypes.get(1)));

                if (!isValid){
                    String correspondingMessageUUID = messageAndConnectionMessageMap.get(jsonResult.getCorrespondingUUID());
                    Message message = weMessage.get().getMessageDatabase().getMessageByUuid(correspondingMessageUUID);

                    message.setHasErrored(true);
                    messageManager.updateMessage(correspondingMessageUUID, message, false);
                }

            } else {
                JSONAction jsonAction = (JSONAction) serverMessage.getOutgoing(JSONAction.class, byteArrayAdapter);
                boolean isValid = validateActionReturnType(messageManager, jsonAction, returnTypes.get(0));

                if (isValid) {
                    performAction(messageManager, jsonAction);
                }
            }

        }else if (connectionMessage instanceof ClientMessage){
            ClientMessage clientMessage = (ClientMessage) connectionMessage;
            boolean isAMessage;

            try {
                JSONMessage m = (JSONMessage) clientMessage.getIncoming(JSONMessage.class, byteArrayAdapter);
                isAMessage = true;
            }catch (Exception ex){
                try {
                    JSONAction a = (JSONAction) clientMessage.getIncoming(JSONAction.class, byteArrayAdapter);
                    isAMessage = false;
                }catch(Exception e){
                    sendLocalBroadcast(weMessage.BROADCAST_RESULT_PROCESS_ERROR, null);
                    AppLogger.error("Could not process result because an error occurred while parsing JSON", e);
                    return;
                }
            }

            if (isAMessage){
                JSONMessage jsonMessage = (JSONMessage) clientMessage.getIncoming(JSONMessage.class, byteArrayAdapter);
                boolean isValid;

                if (returnTypes.size() == 1){
                    isValid = validateMessageReturnType(messageManager, jsonMessage, returnTypes.get(0));
                }else {
                    isValid = (validateMessageReturnType(messageManager, jsonMessage, returnTypes.get(0)) && validateMessageReturnType(messageManager, jsonMessage, returnTypes.get(1)));
                }

                if (!isValid){
                    String correspondingMessageUUID = messageAndConnectionMessageMap.get(jsonResult.getCorrespondingUUID());
                    Message message = weMessage.get().getMessageDatabase().getMessageByUuid(correspondingMessageUUID);

                    message.setHasErrored(true);
                    messageManager.updateMessage(correspondingMessageUUID, message, false);
                }

            } else {
                JSONAction jsonAction = (JSONAction) clientMessage.getIncoming(JSONAction.class, byteArrayAdapter);
                boolean isValid = validateActionReturnType(messageManager, jsonAction, returnTypes.get(0));

                if (isValid) {
                    performAction(messageManager, jsonAction);
                }
            }
        }
    }

    private void performAction(MessageManager messageManager, JSONAction jsonAction){
        MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();
        ActionType actionType = ActionType.fromCode(jsonAction.getActionType());
        String[] args = jsonAction.getArgs();

        if (actionType == null){
            sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
            AppLogger.error("The JSONAction could not be performed because ActionType was null", new NullPointerException());
            return;
        }

        switch (actionType) {
            case ADD_PARTICIPANT:
                GroupChat apGroupChat = messageDatabase.getGroupChatByName(args[0], args[2]);
                Contact apContact = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(args[3]));

                if (messageDatabase.getHandleByHandleID(args[3]) == null) {
                    messageDatabase.addHandle(new Handle(UUID.randomUUID(), args[3], Handle.HandleType.IMESSAGE));
                }

                if (apContact == null) {
                    messageManager.addContact(new Contact(UUID.randomUUID(), null, null, messageDatabase.getHandleByHandleID(args[3]), null), false);
                    apContact = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(args[3]));
                }

                if (apGroupChat == null) {
                    Bundle extras = new Bundle();
                    extras.putString(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE, getParentService().getString(R.string.action_perform_error_group_not_found,
                            getParentService().getString(R.string.action_add_participant)));
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, extras);
                    AppLogger.error("Could not perform JSONAction Add Participant because group chat was not found with name: " + args[0] + " and last message: " + args[2],
                            new NullPointerException());
                    return;
                }
                messageManager.addParticipantToGroup(apGroupChat, apContact, Calendar.getInstance().getTime(), false);
                break;

            case REMOVE_PARTICIPANT:
                GroupChat rpGroupChat = messageDatabase.getGroupChatByName(args[0], args[2]);
                Contact rpContact = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(args[3]));

                if (rpGroupChat == null) {
                    Bundle extras = new Bundle();
                    extras.putString(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE, getParentService().getString(R.string.action_perform_error_group_not_found,
                            getParentService().getString(R.string.action_remove_participant)));
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, extras);
                    AppLogger.error("Could not perform JSONAction Remove Participant because group chat was not found with name: " + args[0] + " and last message: " + args[2],
                            new NullPointerException());
                    return;
                }

                if (rpContact == null) {
                    Bundle extras = new Bundle();
                    extras.putString(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE, getParentService().getString(R.string.action_perform_error_contact_not_found,
                            getParentService().getString(R.string.action_remove_participant)));
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, extras);
                    AppLogger.error("Could not perform JSONAction Remove Participant because contact with Handle ID: " + args[3] + " was not found.", new NullPointerException());
                    return;
                }
                messageManager.removeParticipantFromGroup(rpGroupChat, rpContact, Calendar.getInstance().getTime(), false);
                break;

            case RENAME_GROUP:
                GroupChat rnGroupChat = messageDatabase.getGroupChatByName(args[0], args[2]);

                if (rnGroupChat == null) {
                    Bundle extras = new Bundle();
                    extras.putString(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE, getParentService().getString(R.string.action_perform_error_group_not_found,
                            getParentService().getString(R.string.action_rename_group)));
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, extras);
                    AppLogger.error("Could not perform JSONAction Rename Group because group chat was not found with name: " + args[0] + " and last message: " + args[2],
                            new NullPointerException());
                    return;
                }
                messageManager.renameGroupChat(rnGroupChat, args[3], Calendar.getInstance().getTime(), false);
                break;

            case CREATE_GROUP:
                List<String> participants = new ArrayList<>(Arrays.asList(args[1].split(",")));
                List<Contact> contacts = new ArrayList<>();

                for (String s : participants) {
                    if (messageDatabase.getHandleByHandleID(s) == null) {
                        messageDatabase.addHandle(new Handle(UUID.randomUUID(), s, Handle.HandleType.IMESSAGE));
                    }
                }

                for (String s : participants) {
                    if (messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)) == null) {
                        messageManager.addContact(new Contact(UUID.randomUUID(), null, null, messageDatabase.getHandleByHandleID(s), null), false);
                        contacts.add(messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(s)));
                    }
                }
                messageManager.addChat(new GroupChat(UUID.randomUUID(), null, null, null, null, true, true, args[0], contacts), false);
                break;

            case LEAVE_GROUP:
                GroupChat lvGroupChat = messageDatabase.getGroupChatByName(args[0], args[2]);

                if (lvGroupChat == null) {
                    Bundle extras = new Bundle();
                    extras.putString(weMessage.BUNDLE_ACTION_PERFORM_ALTERNATE_ERROR_MESSAGE, getParentService().getString(R.string.action_perform_error_group_not_found,
                            getParentService().getString(R.string.action_leave_group)));
                    sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, extras);
                    AppLogger.error("Could not perform JSONAction Rename Group because group chat was not found with name: " + args[0] + " and last message: " + args[2],
                            new NullPointerException());
                    return;
                }
                messageManager.leaveGroup(lvGroupChat, Calendar.getInstance().getTime(), false);
                break;

            default:
                sendLocalBroadcast(weMessage.BROADCAST_ACTION_PERFORM_ERROR, null);
                AppLogger.error("Could not perform JSONAction because an unsupported action type was received", new NullPointerException());
                break;
        }
    }

    private boolean validateMessageReturnType(MessageManager messageManager, JSONMessage jsonMessage, ReturnType returnType){
        if (returnType == null){
            messageManager.alertMessageSendFailure(jsonMessage, ReturnType.UNKNOWN_ERROR);
            AppLogger.error(TAG, "No return type was found", new Exception());
            return false;
        }

        switch (returnType){
            case UNKNOWN_ERROR:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.UNKNOWN_ERROR);
                return false;
            case SENT:
                break;
            case INVALID_NUMBER:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.INVALID_NUMBER);
                return false;
            case NUMBER_NOT_IMESSAGE:
                //TODO: Add SMS Implementation Later

                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.NUMBER_NOT_IMESSAGE);
                return false;
            case GROUP_CHAT_NOT_FOUND:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.GROUP_CHAT_NOT_FOUND);
                return false;
            case NOT_SENT:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.NOT_SENT);
                return false;
            case SERVICE_NOT_AVAILABLE:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.SERVICE_NOT_AVAILABLE);
                return false;
            case FILE_NOT_FOUND:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.FILE_NOT_FOUND);
                return false;
            case NULL_MESSAGE:
                break;
            case ASSISTIVE_ACCESS_DISABLED:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.ASSISTIVE_ACCESS_DISABLED);
                return false;
            case UI_ERROR:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.UI_ERROR);
                return false;
            case ACTION_PERFORMED:
                break;
            default:
                messageManager.alertMessageSendFailure(jsonMessage, ReturnType.UNKNOWN_ERROR);
                AppLogger.error(TAG, "An unsupported ReturnType enum was found", new Exception());
                return false;
        }
        return true;
    }

    private boolean validateActionReturnType(MessageManager messageManager, JSONAction jsonAction, ReturnType returnType){
        if (returnType == null){
            messageManager.alertActionPerformFailure(jsonAction, ReturnType.UNKNOWN_ERROR);
            AppLogger.error(TAG, "No return type was found", new Exception());
            return false;
        }

        switch (returnType){
            case UNKNOWN_ERROR:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.UNKNOWN_ERROR);
                return false;
            case INVALID_NUMBER:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.INVALID_NUMBER);
                return false;
            case NUMBER_NOT_IMESSAGE:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.NUMBER_NOT_IMESSAGE);
                return false;
            case GROUP_CHAT_NOT_FOUND:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.GROUP_CHAT_NOT_FOUND);
                return false;
            case NOT_SENT:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.NOT_SENT);
                return false;
            case SERVICE_NOT_AVAILABLE:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.SERVICE_NOT_AVAILABLE);
                return false;
            case ASSISTIVE_ACCESS_DISABLED:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.ASSISTIVE_ACCESS_DISABLED);
                return false;
            case UI_ERROR:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.UI_ERROR);
                return false;
            case ACTION_PERFORMED:
                break;
            default:
                messageManager.alertActionPerformFailure(jsonAction, ReturnType.UNKNOWN_ERROR);
                AppLogger.error(TAG, "An unsupported ReturnType enum was found", new Exception());
                return false;
        }
        return true;
    }

    private void updateMessage(MessageManager messageManager, Message existingMessage, JSONMessage jsonMessage, boolean overrideAll){
        MessageDatabase messageDatabase = weMessage.get().getMessageDatabase();
        Message newData = new Message().setUuid(existingMessage.getUuid()).setAttachments(existingMessage.getAttachments()).setText(existingMessage.getText())
                .setDateSent(jsonMessage.getDateSent()).setDateDelivered(jsonMessage.getDateDelivered()).setDateRead(jsonMessage.getDateRead()).setHasErrored(jsonMessage.getErrored())
                .setIsSent(jsonMessage.isSent()).setDelivered(jsonMessage.isDelivered()).setRead(jsonMessage.isRead()).setFinished(jsonMessage.isFinished()).setFromMe(existingMessage.isFromMe());

        if (overrideAll){
            Contact sender;

            if (messageDatabase.getChatByMacGuid(jsonMessage.getChat().getMacGuid()).getChatType() == Chat.ChatType.PEER){
                if (jsonMessage.isFromMe()){
                    sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByAccount(weMessage.get().getCurrentAccount()));
                }else {
                    if (StringUtils.isEmpty(jsonMessage.getHandle())) {
                        sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(jsonMessage.getHandle()));
                    }else {
                        sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(jsonMessage.getHandle()));
                    }
                }
            }else {
                if (StringUtils.isEmpty(jsonMessage.getHandle())) {
                    sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByAccount(weMessage.get().getCurrentAccount()));
                } else {
                    sender = messageDatabase.getContactByHandle(messageDatabase.getHandleByHandleID(jsonMessage.getHandle()));
                }
            }

            newData.setMacGuid(jsonMessage.getMacGuid()).setChat(messageDatabase.getChatByMacGuid(jsonMessage.getChat().getMacGuid()))
                    .setSender(sender);
        }else {
            newData.setMacGuid(existingMessage.getMacGuid()).setChat(messageDatabase.getChatByUuid(existingMessage.getChat().getUuid().toString()))
                    .setSender(messageDatabase.getContactByHandle(existingMessage.getSender().getHandle()));
        }

        messageManager.updateMessage(existingMessage.getUuid().toString(), newData, false);
    }

    private List<ReturnType> parseResults(List<Integer> integerList){
        List<ReturnType> returnList = new ArrayList<>();

        for (Integer i : integerList){
            returnList.add(ReturnType.fromCode(i));
        }

        return returnList;
    }
}