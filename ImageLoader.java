package com.example.administrator.layouttest002.ImageLoaderDemo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.example.administrator.layouttest002.R;
import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ImageLoader {
    private static final String TAG = "ImageLoader";
    private LruCache<String,Bitmap> lruCache;
    private DiskLruCache diskLruCache;
    private Context mContext;
    private static final int CPU_COUNT=Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE=CPU_COUNT+1;
    private static final int MAXIMUM_POOL_SIZE=CPU_COUNT*2+1;
    private static final long KEEP_ALIVE=10L;
    private static final int MESSAGE_POST_RESULT=1;
    private static final long DISK_CACHE_SIZE=1024*1024*50;
    private static final int DISK_CACHE_INDEX=0;
    private boolean mIsDiskLruCacheCreated=false;

    private ImageResizer mImageResizer=new ImageResizer();

    private static final ThreadFactory threadFactory=new ThreadFactory() {
        private final AtomicInteger atomicInteger=new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r,"ImageLoader#"+atomicInteger.getAndIncrement());
        }
    };

    public static final Executor THREAD_POOL_EXECUTOR=new ThreadPoolExecutor(CORE_POOL_SIZE,MAXIMUM_POOL_SIZE,KEEP_ALIVE, TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>(),threadFactory);

    private Handler mMainHandler=new Handler(Looper.getMainLooper()){
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result= (LoaderResult) msg.obj;
            ImageView imageView=result.imageView;
            imageView.setImageBitmap(result.bitmap);
        }
    };

    //私有化构造方法
    private ImageLoader(Context context){
        mContext=context.getApplicationContext();
        //设置内存缓存大小为该进程最大可用内存的1/8
        int maxMemory= (int) (Runtime.getRuntime().maxMemory()/1024);
        int cacheMemory=maxMemory/8;
        //初始化内存缓存类
        lruCache=new LruCache<String ,Bitmap>(cacheMemory){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes()*value.getHeight()/1024;
            }
        };
        //初始化存储设备缓存类
        File diskCacheDir=new File(mContext.getCacheDir(),"diskCacheDir");
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir)>DISK_CACHE_SIZE){
            try{
                diskLruCache=DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated=true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    //通过该方法获取该图片加载工具类的实例
    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    //内存缓存的存入
    private void addBitmapToMemoryCache(String key,Bitmap bitmap){
        if (getBitmapFromMCache(key)==null){
            lruCache.put(key,bitmap);
        }
    }
    //内存缓存的取出
    private Bitmap getBitmapFromMCache(String key){
        return lruCache.get(key);
    }
    //通过http请求访问网络图片,存入存储设备缓存,以及从存储设备缓存中取出图片,这两个方法都要在子线程中执行
    private Bitmap loadBitmapToDCache(String url,int reqWidth,int reqHeight) throws IOException {
        if (Looper.myLooper()==Looper.getMainLooper()){
            throw new RuntimeException("Can not load network from UI Thread");
        }
        if (diskLruCache==null){
            return null;
        }

        String key=hashKeyFormUrl(url);
        DiskLruCache.Editor editor=diskLruCache.edit(key);
        if (editor!=null){
            OutputStream outputStream=editor.newOutputStream(DISK_CACHE_INDEX);
            if (downloadUrlToStream(url,outputStream)){
                editor.commit();
            }else {
                editor.abort();
            }
            diskLruCache.flush();
        }
        return loadBitmapFromDCache(url,reqWidth,reqHeight);
    }
    //从存储设备缓存中取出图片,在子线程中执行
    private Bitmap loadBitmapFromDCache(String url,int reqWidth,int reqHeight) throws IOException {
        if (Looper.myLooper()==Looper.getMainLooper()){
            Log.w(TAG, "load bitmap from DiskCache in UI Thread,it's not recommanded!");
        }
        if (diskLruCache==null){
            return null;
        }
        Bitmap bitmap=null;
        String key=hashKeyFormUrl(url);
        DiskLruCache.Snapshot snapshot=diskLruCache.get(key);
        if (snapshot!=null){
            FileInputStream fileInputStream= (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor=fileInputStream.getFD();
            bitmap=mImageResizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor,reqWidth,reqHeight);
            if (bitmap!=null){
                //加入内存缓存
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;
    }

    //同步加载图片,先看内存,再看存储设备,最后网络请求
    public Bitmap loadBitmap(String url,int reqWidth,int reqHeight)  {
        Bitmap bitmap=getBitmapFromMCache(hashKeyFormUrl(url));
        if (bitmap!=null){
            Log.i(TAG, "loadBitmap: 从内存缓存中加载成功");
            return bitmap;
        }
        try {
            bitmap=loadBitmapFromDCache(url,reqWidth,reqHeight);
            if (bitmap!=null){
                Log.i(TAG, "loadBitmap: 从存储设备缓存中加载成功");
                return bitmap;
            }
            bitmap=loadBitmapToDCache(url,reqWidth,reqHeight);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (bitmap==null&&!mIsDiskLruCacheCreated){
            Log.w(TAG, "存储设备可见不足");
            bitmap=downloadBitmapFromUrl(url);
        }
        return bitmap;
    }

    public void bindBitmap(final String url, ImageView imageView){
        bindBitmap(url,imageView,0,0);
    }

    //异步加载图片
    public void bindBitmap(final String url, final ImageView imageView, final int reqWidth, final int reqHeight){

        Bitmap bitmap=getBitmapFromMCache(hashKeyFormUrl(url));
        if (bitmap!=null){
            imageView.setImageBitmap(bitmap);
            return;
        }
        Runnable loadBitmapTask=new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap=loadBitmap(url,reqWidth,reqHeight);
                if (bitmap!=null){
                    LoaderResult result=new LoaderResult(imageView,bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    private long getUsableSpace(File path){
        return path.getUsableSpace();
    }
    //这个是MD5加密方法
    public String hashKeyFormUrl(String str){
        try {
            MessageDigest messageDigest=MessageDigest.getInstance("MD5");
            messageDigest.update(str.getBytes());
            byte[] bytes=messageDigest.digest();
            StringBuilder stringBuilder=new StringBuilder();
            for (byte b:bytes){
                String temp = Integer.toHexString(b & 0xff);
                if (temp.length() == 1) {
                    stringBuilder.append('0');
                }
                stringBuilder.append(temp);
            }
            String result=stringBuilder.toString();
            System.out.println(result);
            return result;
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
    //这个是网络请求然后IO
    public boolean downloadUrlToStream(String urlString,OutputStream outputStream){
        HttpURLConnection urlConnection=null;
        BufferedOutputStream out=null;
        BufferedInputStream in=null;

        try {
            URL url=new URL(urlString);
            urlConnection= (HttpURLConnection) url.openConnection();
            in=new BufferedInputStream(urlConnection.getInputStream());
            out=new BufferedOutputStream(outputStream);
            int len;
            byte[] b=new byte[1024];
            while ((len=in.read(b))!=-1){
                out.write(b,0,len);
            }
            return true;
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            try {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap=null;
        HttpURLConnection urlConnection=null;
        BufferedInputStream inputStream=null;
        try{
            URL url=new URL(urlString);
            urlConnection= (HttpURLConnection) url.openConnection();
            inputStream=new BufferedInputStream(urlConnection.getInputStream());
            bitmap=BitmapFactory.decodeStream(inputStream);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if (urlConnection!=null){
                urlConnection.disconnect();
            }
            if (inputStream!=null){
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return bitmap;
        }
    }

    //该内部类用于对象的线程间传递
    private static class LoaderResult{
        public ImageView imageView;
        public Bitmap bitmap;


        public LoaderResult(ImageView imageView, Bitmap bitmap) {
            this.imageView = imageView;
            this.bitmap = bitmap;
        }
    }
}
