package com.example.entranceguard2;


import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_10;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;

public class MainActivity extends AppCompatActivity {
    private String ipname = null;
    private SurfaceView sView;
    private SurfaceHolder surfaceHolder;
    private int screenWidth, screenHeight;
    private Camera camera; // 定义系统所用的照相机
    private boolean isPreview = false; // 是否在浏览中

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置全屏
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        screenWidth = 640;
        screenHeight = 480;
        sView = (SurfaceView) findViewById(R.id.sView); // 获取界面中SurfaceView组件
        surfaceHolder = sView.getHolder(); // 获得SurfaceView的SurfaceHolder

        // 为surfaceHolder添加一个回调监听器
        surfaceHolder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format,
                                       int width, int height) {
            }

            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                initCamera(); // 打开摄像头
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                // 如果camera不为null ,释放摄像头
                if (camera != null) {
                    if (isPreview)
                        camera.stopPreview();
                    camera.release();
                    camera = null;
                }
                System.exit(0);
            }
        });
        // 设置该SurfaceView自己不维护缓冲
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    private void initCamera() {
        if (!isPreview) {
            camera = Camera.open(1);
        }
        if (camera != null && !isPreview) {
            try {
                Camera.Parameters parameters = camera.getParameters();
                parameters.setPreviewSize(screenWidth, screenHeight); // 设置预览照片的大小
                parameters.setPreviewFpsRange(20, 30); // 每秒显示20~30帧
                parameters.setPictureFormat(ImageFormat.NV21); // 设置图片格式
                parameters.setPictureSize(screenWidth, screenHeight); // 设置照片的大小
                // camera.setParameters(parameters); // android2.3.3以后不需要此行代码
                camera.setDisplayOrientation(90);//旋转90°
                camera.setPreviewDisplay(surfaceHolder); // 通过SurfaceView显示取景画面
                camera.setPreviewCallback(new StreamIt(ipname)); // 设置回调的类
                camera.startPreview(); // 开始预览
                camera.autoFocus(null); // 自动对焦
            } catch (Exception e) {
                e.printStackTrace();
            }
            isPreview = true;
        }
    }

}

class StreamIt implements Camera.PreviewCallback {
    private String ipname;

    public StreamIt(String ipname) {
        this.ipname = ipname;
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        Camera.Size size = camera.getParameters().getPreviewSize();
        try {
            // 调用image.compressToJpeg（）将YUV格式图像数据data转为jpg格式
            YuvImage image = new YuvImage(data, ImageFormat.NV21, size.width,
                    size.height, null);//YUV420P和NV21两种格式
            if (image != null) {
                ByteArrayOutputStream outstream = new ByteArrayOutputStream();
                image.compressToJpeg(new Rect(0, 0, size.width, size.height),
                        80, outstream);
                outstream.flush();
                // 启用线程将图像数据发送出去
//                Thread th = new MyThread(outstream, ipname);
//                th.start();
            }
        } catch (Exception ex) {
            Log.e("Sys", "Error:" + ex.getMessage());
        }
    }
}

class MyThread extends Thread {
    private byte byteBuffer[] = new byte[1024];
    private OutputStream outsocket;
    private ByteArrayOutputStream myoutputstream;
    private String ipname;
//    private WebSocketClient mSocketClient

    public MyThread(ByteArrayOutputStream myoutputstream, String ipname) {
        this.myoutputstream = myoutputstream;
        this.ipname = ipname;
        try {
            myoutputstream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        try {

            WebSocketClient mSocketClient = new WebSocketClient(new URI(ipname), new Draft_10()) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.d("picher_log", "打开通道" + handshakedata.getHttpStatus());
                }

                @Override
                public void onMessage(String message) {
                    Log.d("picher_log", "接收消息" + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.d("picher_log", "通道关闭");
                }

                @Override
                public void onError(Exception ex) {
                    Log.d("picher_log", "链接错误");
                }
            };
            mSocketClient.connect();
            byte[] buf = myoutputstream.toByteArray();
            mSocketClient.send(buf);
            mSocketClient.close();
////            // 将图像数据通过Socket发送出去
//            Socket tempSocket = new Socket(ipname, 6000);
//            outsocket = tempSocket.getOutputStream();
//            ByteArrayInputStream inputstream = new ByteArrayInputStream(
//                    myoutputstream.toByteArray());
//            int amount;
//            while ((amount = inputstream.read(byteBuffer)) != -1) {
//                outsocket.write(byteBuffer, 0, amount);
//            }
//            myoutputstream.flush();
//            myoutputstream.close();
//            tempSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}