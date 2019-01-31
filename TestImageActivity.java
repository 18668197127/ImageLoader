package com.example.administrator.layouttest002.ImageLoaderDemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import com.example.administrator.layouttest002.R;

public class TestImageActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_image);

        ImageView imageView=findViewById(R.id.imageView);
        String url="http://f.hiphotos.baidu.com/image/pic/item/b7fd5266d016092446517fdadd0735fae7cd34ff.jpg";
        ImageLoader imageLoader=ImageLoader.build(getApplicationContext());
        imageLoader.bindBitmap(url,imageView);
    }
}
