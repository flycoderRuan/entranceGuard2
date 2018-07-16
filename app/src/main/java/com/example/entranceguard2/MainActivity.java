package com.example.entranceguard2;



import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.List;


public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    // WebSocket
    private final static int LOG_MSG_TAG = 0x001;
    private WebSocketClient mWebSocketClient = null;
    private String address = "ws://192.168.0.117:21085/echo";
    private URI uri;
    private static final String TAG = "Entrance Guard";

    // Camera
    private SurfaceView surfaceview;
    private SurfaceHolder surfaceHolder;
    private Camera camera; // 定义系统所用的照相机
    private Camera.Parameters parameters;

    //    // 帧率
//    private int width = 640;
//    private int height = 480;
    private int width = 1280;
    private int height = 720;

    // 帧率
    private int framerate = 24;
    // 比特率
    private int biterate = width * height * 3;

    private Handler mHandler;
    // WebSocket线程
    Thread wsThread;
    Thread presentThread;

    private MediaCodec mediaCodec;
    private final int TIMEOUT_USEC = 12000;

    private BufferedOutputStream outputStream;
    private FileOutputStream fileOutputStream;
    private TextView textView;
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
//        Log.i("onCreateId:",Thread.currentThread()+"");
//        String str = "onCreateId:" + Thread.currentThread().getId();

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textView = (TextView) findViewById(R.id.textView);
        mHandler = new Handler(){
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what){
                    case LOG_MSG_TAG:
                        textView.append(msg.obj.toString());
                        break;
                }
            }
        };
        createfile();

//        try{
//            fileOutputStream.write(str.getBytes());
//            fileOutputStream.flush();
//        }catch (Exception e){
//            e.printStackTrace();
//        }
        initSockect();
        initSurfaceView();

        if (!SupportAvcCodec()) {
            Log.e(TAG, "不支持编码");
            onDestroy();
        }

//        DisplayMetrics dm =getResources().getDisplayMetrics();
//        int w_screen = dm.widthPixels;//800
//        int h_screen = dm.heightPixels;//1232
//        int i = 0;
    }

    private static String logPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/test3.txt";
    private static String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/test3.yuv";
    // 生成视频文件，保存本地
    private void createfile(){
        File file = new File(path);
        File logFile = new File(logPath);

        if(file.exists()){
            file.delete();
        }
        if(logFile.exists()){
            file.delete();
        }
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
            fileOutputStream = new FileOutputStream(logFile);

        } catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
//        String str = "surfaceCreatedId:" + Thread.currentThread().getId();
//        try{
//            fileOutputStream.write(str.getBytes());
//            fileOutputStream.flush();
//        }catch (Exception e){
//            e.printStackTrace();
//        }
        Log.i("surfaceCreatedId:",Thread.currentThread()+"");
        initCamera();
        initMediaCodec();
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
//        Log.d("11111","11111");
        if (null != camera) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
            mWebSocketClient.close();

            try {
                outputStream.close();
                fileOutputStream.close();
            }catch (Exception e){
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("NewApi")
    @Override
    public void onPreviewFrame(final byte[] bytes, Camera camera) {

        presentThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    YuvImage image = new YuvImage(bytes, ImageFormat.NV21, width, height, null);
//                    outputStream.write( image );
                    if (image != null) {
                        Log.i("image",image.getWidth()+" "+image.getHeight());
                        ByteArrayOutputStream stream = new ByteArrayOutputStream();
                        image.compressToJpeg(new Rect(0, 0, width, height), 80, stream);
                        byte[] imageBytes = stream.toByteArray();
                        Bitmap tempBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);

                        //镜像旋转
                        Matrix m = new Matrix();
                        m.setScale(-1,1);
                        Bitmap reverseBitmap = Bitmap.createBitmap(tempBitmap,0,0,width,height,m,true);

                        Canvas c = surfaceHolder.lockCanvas();
                        Paint mPaint = new Paint();
                        c.drawBitmap(reverseBitmap,0 ,0, mPaint);
                        surfaceHolder.unlockCanvasAndPost(c);
                    }
                    Log.i("response","ok");
                } catch (Exception ex) {
                    Log.e("Sys", "Error:" + ex.getMessage());
                }
            }
        });
//        presentThread.start();

        wsThread = new Thread(new Runnable() {
            @Override
            public void run() {
                String str = "onPreviewFrameId:" + Thread.currentThread().getId() + "\n";

                Date startDate  =  new  Date(System.currentTimeMillis());
                byte[] input = bytes;
                byte[] yuv420sp = new byte[width * height * 3/2];
                NV21ToNV12(input, yuv420sp, width, height);
                input = yuv420sp;

                byte[] temp = new byte[width * height * 3/2];
                temp = yuv420sp;

//                mWebSocketClient.send(yuv420sp);
                Date endDate  =  new  Date(System.currentTimeMillis());

                str += "startTime: " + startDate.toString() + "\n";
                str += "endTime : " + endDate.toString() + "\n";
                Message msg = new Message();
                msg.what = LOG_MSG_TAG;
                msg.obj = str;
                mHandler.sendMessage(msg);
//                try{
//                    fileOutputStream.write(str.getBytes());
//                    fileOutputStream.write(startDate.toString().getBytes());
//                    fileOutputStream.write(endDate.toString().getBytes());
//                    fileOutputStream.flush();
//                }catch (Exception e){
//                    e.printStackTrace();
//                }
//                try {
//                    outputStream.write(yuv420sp, 0, yuv420sp.length);//将输出的h264数据保存为文件，用vlc就可以播放
//                }catch (Exception e){
//                    e.printStackTrace();
//                }
//
//                try {
//                    Thread.sleep(30);
//                }catch (Exception e){
//                    e.printStackTrace();
//                }
//                if (input != null && mediaCodec != null) {
//                    try {
//                        ByteBuffer[] inputBuffers = mediaCodec.getInputBuffers();//拿到输入缓冲区,用于传送数据进行编码
//                        ByteBuffer[] outputBuffers = mediaCodec.getOutputBuffers();//拿到输出缓冲区,用于取到编码后的数据
//                        int inputBufferIndex = mediaCodec.dequeueInputBuffer(-1);
//                        if (inputBufferIndex >= 0) {//当输入缓冲区有效时,就是>=0
//                            ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
//                            inputBuffer.clear();
//                            inputBuffer.put(input);//往输入缓冲区写入数据,
//                            //                    //五个参数，第一个是输入缓冲区的索引，第二个数据是输入缓冲区起始索引，第三个是放入的数据大小，第四个是时间戳，保证递增就是
//                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, System.nanoTime() / 1000, 0);
//
//                        }
//
//                        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
//                        int outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);//拿到输出缓冲区的索引
//                        while (outputBufferIndex >= 0) {
//                            ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
//                            byte[] outData = new byte[bufferInfo.size];
//                            outputBuffer.get(outData);
//                            //outData就是输出的h264数据
//                            mWebSocketClient.send(yuv420sp);
////                            Log.d("byte_size:",String.valueOf(bytes.length));
////                            outputStream.write(outData, 0, outData.length);//将输出的h264数据保存为文件，用vlc就可以播放
//
//                            mediaCodec.releaseOutputBuffer(outputBufferIndex, false);
//                            outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
//                        }
//
//                    } catch (Throwable t) {
//                        t.printStackTrace();
//                    }
//                }
            }
        });
        wsThread.start();
        presentThread.start();
    }


    private void initSurfaceView() {
        surfaceview = (SurfaceView) findViewById(R.id.sView);
        surfaceHolder = surfaceview.getHolder();
        surfaceHolder.addCallback(this);
    }

    private void initCamera() {
        try {
            camera = Camera.open(Camera.getNumberOfCameras() > 1 ? 1 : 0);
//            camera = Camera.open(0);
        } catch (Exception e) {
            Log.d("camera open error.","open error");
            e.printStackTrace();
        }

        if (camera != null) {
            try {
                camera.setPreviewCallback(this);
                camera.setDisplayOrientation(90);
                int frameSize = width * height * 3 / 2;
                byte[] callbackBuffer = new byte[frameSize];
                camera.addCallbackBuffer(callbackBuffer);
                if (parameters == null) {
                    parameters = camera.getParameters();
                }
                parameters.setPreviewFormat(ImageFormat.NV21);
//                parameters.setPreviewFormat(ImageFormat.YV12);
                parameters.setPreviewSize(width, height);
                parameters.setPreviewFrameRate(framerate); // 设置帧率
                camera.setParameters(parameters);
//                if (null != surfaceHolder) {
//                    camera.setPreviewDisplay(surfaceHolder);
//                }
                camera.startPreview();
//                camera.autoFocus(null); // 自动对焦
            } catch (Exception e) {
                Log.d("camera set params error","set params error");
                e.printStackTrace();
            }
        }
    }

    private void initSockect() {
        try {
            uri = new URI(address);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        if (null == mWebSocketClient) {
            mWebSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    Log.i(TAG, "onOpen: ");
//                    mWebSocketClient.send("---------------ws-opened---------------");
//                    mWebSocketClient.send("hello rzq");
                }

                @Override
                public void onMessage(String s) {
                    Log.i(TAG, "onMessage: " + s);
                }

                @Override
                public void onClose(int i, String s, boolean b) {
                    Log.i(TAG, "onClose: ");
                }

                @Override
                public void onError(Exception e) {
                    Log.i(TAG, "onError: ");
                }
            };
            mWebSocketClient.connect();
        }
    }

    @SuppressLint("NewApi")
    private void initMediaCodec() {
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", width, height);
        int format = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
//        int format = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        if( format != 0 ){
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, format);

        }
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, biterate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framerate);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc");
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//        final Surface inputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();
    }

    private boolean SupportAvcCodec() {
        if (Build.VERSION.SDK_INT >= 18) {
            for (int j = MediaCodecList.getCodecCount() - 1; j >= 0; j--) {
                MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(j);

                String[] types = codecInfo.getSupportedTypes();
                for (int i = 0; i < types.length; i++) {
                    if (types[i].equalsIgnoreCase("video/avc")) {
                        return true;
                    }
                }

            }
        }
        return false;
    }

    private void NV21ToNV12(byte[] nv21,byte[] nv12,int width,int height){
        if(nv21 == null || nv12 == null)return;
        int framesize = width*height;
        int i = 0,j = 0;
        System.arraycopy(nv21, 0, nv12, 0, framesize);
        for(i = 0; i < framesize; i++){
            nv12[i] = nv21[i];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j-1] = nv21[j+framesize];
        }
        for (j = 0; j < framesize/2; j+=2)
        {
            nv12[framesize + j] = nv21[j+framesize-1];
        }
    }
}