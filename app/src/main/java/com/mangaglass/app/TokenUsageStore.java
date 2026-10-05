package com.mangaglass.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.time.LocalDate;

/** One aggregate per local calendar day. No credentials, images, prompts or responses are stored. */
final class TokenUsageStore extends SQLiteOpenHelper implements TokenUsage.Recorder {
    private static TokenUsageStore instance;
    static synchronized TokenUsageStore get(Context context) {
        if(instance==null) instance=new TokenUsageStore(context.getApplicationContext(),"token_usage.db");
        return instance;
    }
    TokenUsageStore(Context context,String filename) { super(context,filename,null,1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily (day TEXT PRIMARY KEY, requests INTEGER NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL, total INTEGER NOT NULL, input_known INTEGER NOT NULL, output_known INTEGER NOT NULL, total_known INTEGER NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { throw new IllegalStateException("Unsupported usage database version"); }
    @Override public synchronized void record(String day,TokenUsage usage) {
        LocalDate.parse(day);
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO daily VALUES (?,0,0,0,0,0,0,0)",new Object[]{day});
            db.execSQL("UPDATE daily SET requests=requests+1, input=input+?, output=output+?, total=total+?, input_known=input_known+?, output_known=output_known+?, total_known=total_known+? WHERE day=?",
                    new Object[]{orZero(usage.input),orZero(usage.output),orZero(usage.total),known(usage.input),known(usage.output),known(usage.total),day});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized Day query(LocalDate day) {
        try(Cursor row=getReadableDatabase().rawQuery("SELECT requests,input,output,total,input_known,output_known,total_known FROM daily WHERE day=?",new String[]{day.toString()})) {
            if(!row.moveToFirst()) return new Day(new long[7]);
            long[] values=new long[7]; for(int i=0;i<values.length;i++) values[i]=row.getLong(i);
            return new Day(values);
        }
    }
    private static long orZero(Long value) { return value==null?0:value; }
    private static int known(Long value) { return value==null?0:1; }
    static final class Day {
        final long requests,input,output,total,inputKnown,outputKnown,totalKnown;
        Day(long[] values) { requests=values[0]; input=values[1]; output=values[2]; total=values[3]; inputKnown=values[4]; outputKnown=values[5]; totalKnown=values[6]; }
        String display(long sum,long known) {
            if(requests==0) return "0";
            if(known==0) return "未返回";
            return String.format(java.util.Locale.CHINA,"%,d",sum)+(known<requests?"（另有 "+(requests-known)+" 次未返回）":"");
        }
    }
}
