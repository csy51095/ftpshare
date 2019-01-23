package com.github.ghmxr.ftpshare.services;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.util.Log;

import com.github.ghmxr.ftpshare.Constants;
import com.github.ghmxr.ftpshare.R;
import com.github.ghmxr.ftpshare.data.AccountItem;
import com.github.ghmxr.ftpshare.utils.APUtil;
import com.github.ghmxr.ftpshare.utils.MySQLiteOpenHelper;
import com.github.ghmxr.ftpshare.utils.ValueUtil;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.util.ArrayList;
import java.util.List;

public class FtpService extends Service {
    public static FtpServer server;
    public static List<AccountItem> list_account=new ArrayList<>();
    public static PowerManager.WakeLock wakeLock;
    public static FtpService ftpService;
    private static MyHandler handler;
    public static OnFTPServiceDestroyedListener listener;

    public static final int MESSAGE_START_FTP_COMPLETE=1;
    public static final int MESSAGE_START_FTP_ERROR=-1;
    public static final int MESSAGE_WAKELOCK_ACQUIRE=5;
    public static final int MESSAGE_WAKELOCK_RELEASE=6;

    @Override
    public void onCreate() {
        super.onCreate();
        ftpService=this;
        handler=new MyHandler();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try{
            new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (FtpService.class){
                        refreshAccountList(FtpService.this);
                        try{
                            startFTPService();
                            sendEmptyMessage(MESSAGE_START_FTP_COMPLETE);
                        }catch (Exception e){
                            e.printStackTrace();
                            Message msg=new Message();
                            msg.what=MESSAGE_START_FTP_ERROR;
                            msg.obj=e;
                            sendMessage(msg);
                        }
                    }
                }
            }).start();
        }catch (Exception e){
            e.printStackTrace();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startService(Activity activity){
        Intent intent=new Intent(activity,FtpService.class);
        activity.startService(intent);
    }

    public static void refreshAccountList(Context context){
        list_account.clear();
        SharedPreferences settings=context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);
        if(settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE,Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT)){
            AccountItem item=new AccountItem();
            item.account=Constants.FTPConsts.NAME_ANONYMOUS;
            item.password="";
            item.path=settings.getString(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT);
            item.writable=settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE,Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE_DEFAULT);
            list_account.add(item);
            return;
        }
        try{
            SQLiteDatabase db= new MySQLiteOpenHelper(context).getWritableDatabase();
            Cursor cursor=db.rawQuery("select * from "+Constants.SQLConsts.TABLE_NAME,null);
            while (cursor.moveToNext()){
                try{
                    AccountItem item=new AccountItem();
                    item.account=cursor.getString(cursor.getColumnIndex(Constants.SQLConsts.COLUMN_ACCOUNT_NAME));
                    item.password=cursor.getString(cursor.getColumnIndex(Constants.SQLConsts.COLUMN_PASSWORD));
                    item.path=cursor.getString(cursor.getColumnIndex(Constants.SQLConsts.COLUMN_PATH));
                    item.writable=cursor.getInt(cursor.getColumnIndex(Constants.SQLConsts.COLUMN_WRITABLE))==1;
                    list_account.add(item);
                }catch (Exception e){e.printStackTrace();}
            }
            cursor.close();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    private void startFTPService() throws Exception{
        FtpServerFactory factory=new FtpServerFactory();
        SharedPreferences settings = getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE);
        List<Authority> authorities_writable = new ArrayList<>();
        authorities_writable.add(new WritePermission());
        if(settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE,Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT)){
            BaseUser baseUser = new BaseUser();
            baseUser.setName(Constants.FTPConsts.NAME_ANONYMOUS);
            baseUser.setPassword("");
            baseUser.setHomeDirectory(settings.getString(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT));
            if(settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE,Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE_DEFAULT)){
                baseUser.setAuthorities(authorities_writable);
            }
            factory.getUserManager().save(baseUser);
        }else{
            for(AccountItem item:list_account){
                BaseUser baseUser = new BaseUser();
                baseUser.setName(item.account);
                baseUser.setPassword(item.password);
                baseUser.setHomeDirectory(item.path);
                if(item.writable) baseUser.setAuthorities(authorities_writable);
                factory.getUserManager().save(baseUser);
            }
        }

        ListenerFactory lfactory = new ListenerFactory();
        lfactory.setPort(settings.getInt(Constants.PreferenceConsts.PORT_NUMBER,Constants.PreferenceConsts.PORT_NUMBER_DEFAULT)); //设置端口号 非ROOT不可使用1024以下的端口
        factory.addListener("default", lfactory.createListener());
        ConnectivityManager manager=null;
        try{
           manager=(ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        }catch (Exception e){e.printStackTrace();}
        if(manager!=null){
            NetworkInfo info=manager.getActiveNetworkInfo();
            if((info==null||info.getType()!=ConnectivityManager.TYPE_WIFI)&&!APUtil.isAPEnabled(this)) {
                throw new Exception("There is no active network connection");
            }
        }
        try{
            if(server!=null) server.stop();
        }catch (Exception e){}
        server=factory.createServer();
        server.start();
    }

    public void stopFTPService(){
        /*try{
            if(wakeLock!=null) wakeLock.release();
            wakeLock=null;
        }catch (Exception e){}*/
        try{
            //server.stop();
            //server=null;
            stopSelf();
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public static void sendEmptyMessage(int what){
        try{
            handler.sendEmptyMessage(what);
        }catch (Exception e){}
    }

    public static void sendMessage(Message msg){
        try{
            handler.sendMessage(msg);
        }catch (Exception e){}
    }

    private void processMessage(Message msg){
        try{
            switch (msg.what){
                default:break;
                case MESSAGE_START_FTP_COMPLETE:{
                    if(getSharedPreferences(Constants.PreferenceConsts.FILE_NAME,Context.MODE_PRIVATE).getBoolean(Constants.PreferenceConsts.WAKE_LOCK,Constants.PreferenceConsts.WAKE_LOCK_DEFAULT)){
                        sendEmptyMessage(MESSAGE_WAKELOCK_ACQUIRE);
                    }else {
                        sendEmptyMessage(MESSAGE_WAKELOCK_RELEASE);
                    }
                    Log.d("FTP",""+FtpService.isFTPServiceRunning());
                }
                break;
                case MESSAGE_START_FTP_ERROR:{
                    stopSelf();
                }
                break;
                case MESSAGE_WAKELOCK_ACQUIRE:{
                    try{
                        try{
                            if(wakeLock!=null) wakeLock.release();
                        }catch (Exception e){}
                        PowerManager powerManager=(PowerManager)getSystemService(Context.POWER_SERVICE);
                        wakeLock=powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ftp_wake_lock");
                        wakeLock.acquire();
                    }catch (Exception e){e.printStackTrace();}

                }
                break;
                case MESSAGE_WAKELOCK_RELEASE:{
                    try{
                        if(wakeLock!=null) wakeLock.release();
                        wakeLock=null;
                    }catch (Exception e){e.printStackTrace();}
                }
                break;
            }
        }catch (Exception e){e.printStackTrace();}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("onDestroy","onDestroy method called");
        try{
            if(wakeLock!=null){
                wakeLock.release();
                wakeLock=null;
            }
        }catch (Exception e){e.printStackTrace();}
        try{
            if(server!=null){
                server.stop();
                server=null;
            }
        }catch (Exception e){
            e.printStackTrace();
        }
        ftpService=null;
        try{
            if(listener!=null) listener.onFTPServiceDestroyed();
        }catch (Exception e){}

    }

    private static class MyHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            try{
                if(ftpService!=null) ftpService.processMessage(msg);
            }catch (Exception e){e.printStackTrace();}
        }
    }

    public static void setOnFTPServiceDestroyedListener(OnFTPServiceDestroyedListener ls){
        listener=ls;
    }

    public static boolean isFTPServiceRunning(){
        try{
            return server!=null&&!server.isStopped();
        }catch (Exception e){e.printStackTrace();}
        return false;
    }

    public static String getFTPStatusDescription(Context context){
        try{
            if(!isFTPServiceRunning()) return context.getResources().getString(R.string.ftp_status_not_running);
            return context.getResources().getString(R.string.ftp_status_running_head)+ValueUtil.getFTPServiceFullAddress(context);
        }catch (Exception e){e.printStackTrace();}
        return "";
    }

    public interface OnFTPServiceDestroyedListener {
        void onFTPServiceDestroyed();
    }
}