package com.example.administrator.layouttest002.ImageLoaderDemo;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.FileDescriptor;

//这个是图片高效加载类(根据计算出的采样率再进行加载)
public class ImageResizer {
    private static final String TAG = "ImageResizer";
    public ImageResizer(){
    }

    //根据resourceID获取Bitmap,第三第四个参数为需要的宽高
    public Bitmap getScalingBitmapFromResource(Resources resources, int resId, int reqWidth, int reqHeight){
        BitmapFactory.Options options=new BitmapFactory.Options();
        //加载图片宽高
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeResource(resources,resId,options);
        //计算得出采样率
        options.inSampleSize=calculateInSampleSize(options,reqWidth,reqHeight);
        //根据采样率高效的加载图片
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeResource(resources,resId,options);
    }

    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fileDescriptor,int reqWidth,int reqHeight){
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFileDescriptor(fileDescriptor,null,options);
        options.inSampleSize=calculateInSampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeFileDescriptor(fileDescriptor,null,options);

    }
    //根据需要的宽高和图片本身的宽高,计算得出图片采样率
    public int calculateInSampleSize(BitmapFactory.Options options,int reqWidth,int reqHeight){
        if (reqWidth==0||reqHeight==0){
            return 1;
        }
        int height=options.outHeight;
        int width=options.outWidth;
        int inSampleSize=1;

        if (height>reqHeight&&width>reqWidth){
            int halfHeight=height/2;
            int halfWidth=width/2;
            while ((halfHeight/inSampleSize)>=reqHeight&&(halfWidth/inSampleSize)>=reqWidth){
                inSampleSize=inSampleSize*2;
            }
        }
        Log.i(TAG, "sampleSize: "+inSampleSize);
        return inSampleSize;
    }
}
