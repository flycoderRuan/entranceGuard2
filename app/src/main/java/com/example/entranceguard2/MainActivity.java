package com.example.entranceguard2;


import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Properties;

public class MainActivity extends Activity {

    private Integer kStopFrameNum = 0;
    private Integer kSendFrameNum = 0;

    // WebSocket
    private String IpAddress = "";
    private WebSocketClient mWebSocketClient = null;
    private URI uri;

    private final String TAG = "DoorLock";

    private final String BrocastKeycode = "com.lansent.mfrc522.bridge.brocast.keycode"; // 按键广播action
    private BroadcastReceiver msgRecever = null;// 广播接收器

    private final String SHUBAO_BRIDGE_APP = "com.lansent.shubao.bridge";
    private final String SHUBAO_BRIDGE_ServiceLowerMachine = "com.lansent.service.ServiceLowerMachine";
    private final String SHUBAO_BRIDGE_ServiceHandle_NAME = "handle";
    private final String SHUBAO_BRIDGE_ServiceHandle_VALUE_DOOR_OPEN_DELAY = "doorOpenDelay";

    // 按键>键值 映射关系
    protected static final int KEY_0 = 144;
    protected static final int KEY_1 = 145;
    protected static final int KEY_2 = 146;
    protected static final int KEY_3 = 147;
    protected static final int KEY_4 = 148;
    protected static final int KEY_5 = 149;
    protected static final int KEY_6 = 150;
    protected static final int KEY_7 = 151;
    protected static final int KEY_8 = 152;
    protected static final int KEY_9 = 153;

    protected static final int KEY_F1 = 131;
    protected static final int KEY_F2 = 132;
    protected static final int KEY_F3 = 133;
    protected static final int KEY_F4 = 134;

    protected static final int KEY_ENTER = 66;
    protected static final int KEY_BACK = 67;

    private Camera mCamera;
    private int cameraWidth;
    private int cameraHeight;
    private byte[] callbackBuffer;// 摄像头缓存
    private boolean cameraOpenFlag = false;

    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;

    private TextView textView;
    private Handler handler;

    // 用来预览绘制
    private int mChainIdx = 0;
    private JavaCameraFrame[] mCameraFrame;
    private Bitmap mCacheBitmap;
    private boolean initReadyFlag = false;
    private boolean mCameraFrameReady = false;
    private final static Object DrawLock = new Object();

    private Thread drawThread;

    // 用来发送数据
    private boolean initWSReadyFlag = false;
    private Thread sendDataThread;

    private Context mContext;
    private String printMessgae;

    // 配置文件
    private Properties properties;

    private Button login;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initDectectionFunction();
        initLoginButton();
        //设置屏幕常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void initDectectionFunction(){
        mContext = this;
        properties = ProperUtils.getProperties(getApplicationContext());
        IpAddress = properties.getProperty("serverUrl");

        kStopFrameNum = Integer.parseInt(properties.getProperty("stopSendFrameNum"));
        kSendFrameNum = Integer.parseInt(properties.getProperty("startSendFrameNum"));

        mSurfaceView = (SurfaceView) this.findViewById(R.id.sView);
        mSurfaceHolder = mSurfaceView.getHolder();

        textView = (TextView) this.findViewById(R.id.textView);
        textView.setText("");
        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == 0) {
                    textView.setText(printMessgae);
                } else if (msg.what == 1) {
                    textView.setText("");
                }
            }
        };
    }
    private void initLoginButton(){
        login = (Button) findViewById(R.id.login);
        login.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent intent = new Intent(MainActivity.this,LoginActivity.class);
                startActivity(intent);
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();

        printLogError("on Resume");

        // 初始化摄像头
        try {
            mCamera = Camera.open(0);
            Camera.Parameters parameters = mCamera.getParameters();
            cameraWidth = parameters.getPreviewSize().width;
            cameraHeight = parameters.getPreviewSize().height;

            parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
            mCamera.setParameters(parameters);

            callbackBuffer = new byte[cameraWidth * cameraHeight * 3 / 2];

            mCamera.addCallbackBuffer(callbackBuffer);
            mCamera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {

                @Override
                public void onPreviewFrame(final byte[] data, Camera camera) {

                    printLogError("onPreviewFrame:" + data.length);
                    if (initReadyFlag) {
                        synchronized (DrawLock) {
                            // 转码
                            mCameraFrame[mChainIdx].put(data);
                            mCameraFrameReady = true;
                            DrawLock.notify();
                        }
                    }
                }
            });
            mCamera.startPreview();
            cameraOpenFlag = true;
            printLogError("mCamera startPreview:" + cameraWidth + "x" + cameraHeight);
        } catch (Exception e) {
            e.printStackTrace();
            printLogError("can not open camera:"
                    + e.getMessage());
        }

        // 初始化openCV
        if (cameraOpenFlag) {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0, mContext,
                    new BaseLoaderCallback(mContext) {
                        @Override
                        public void onManagerConnected(int status) {
                            switch (status) {
                                case LoaderCallbackInterface.SUCCESS: {
                                    mCacheBitmap = Bitmap.createBitmap(cameraWidth,
                                            cameraHeight, Bitmap.Config.ARGB_8888);

                                    mCameraFrame = new JavaCameraFrame[2];
                                    mCameraFrame[0] = new JavaCameraFrame(
                                            cameraWidth, cameraHeight);
                                    mCameraFrame[1] = new JavaCameraFrame(
                                            cameraWidth, cameraHeight);

                                    initReadyFlag = true;

                                    drawThread = new Thread(new DrawWorker());
                                    drawThread.start();

                                }
                                break;
                                default: {
                                    super.onManagerConnected(status);
                                }
                                break;
                            }
                        }
                    });
        }

        // 初始化WebSocket连接
        try {
            uri = new URI(IpAddress);
            initWebSocketClient();
        } catch (URISyntaxException e) {
            printLogError("WebSocket Issue");
            e.printStackTrace();
        }


        // 按键接收器
        if (msgRecever == null) {
            msgRecever = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String action = intent.getAction();
                        Bundle bundle = intent.getExtras();
                        // 接收按键广播
                        if (action
                                .equals(BrocastKeycode)) {
                            final int keyCode = bundle
                                    .getInt(BrocastKeycode);// 键值

                            if (keyCode != 0) {
                                onKeyDown(keyCode, null);
                            }
                        }
                    } catch (Exception e) {
                    }
                }
            };
        }
        IntentFilter mFilter = new IntentFilter();
        mFilter.addAction(BrocastKeycode);
        registerReceiver(msgRecever, mFilter);// 注册接收器
    }


    @Override
    public void onPause() {
        super.onPause();
        printLogError("on Pause");
        if (drawThread != null) {
            drawThread.interrupt();
            drawThread = null;
        }
        if (sendDataThread != null) {
            sendDataThread.interrupt();
            sendDataThread = null;
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
        if (mCacheBitmap != null) {
            mCacheBitmap.recycle();
            mCacheBitmap = null;
        }

        if (mCameraFrame != null) {
            mCameraFrame[0].release();
            mCameraFrame[1].release();
        }
        if (msgRecever != null) {
            unregisterReceiver(msgRecever);
        }
        if (mWebSocketClient != null) {
            mWebSocketClient.close();
            mWebSocketClient = null;
        }
    }

    /**
     * 处理按键
     *
     * @param keyCode
     * @param event
     * @return
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        printLogError("OnKeyDown");

        switch (keyCode) {
            case KEY_F1:
                break;
            case KEY_F2:
                break;
            case KEY_F3:
                break;
            case KEY_F4:
                break;
            case KEY_0:
                break;
            case KEY_1:
                break;
            case KEY_2:
                break;
            case KEY_3:
                break;
            case KEY_4:
                break;
            case KEY_5:
                break;
            case KEY_6:
                break;
            case KEY_7:
                break;
            case KEY_8:
                break;
            case KEY_9:
                break;
            case KEY_ENTER:
                break;
            case KEY_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void printLogError(String log) {
        Log.e(TAG, log);
    }

    private void openDoor() {
        Intent intent3 = new Intent();
        ComponentName componentName3 = new ComponentName(SHUBAO_BRIDGE_APP, SHUBAO_BRIDGE_ServiceLowerMachine);
        intent3.setComponent(componentName3);
        intent3.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // 塞入携带的字符串
        intent3.putExtra(SHUBAO_BRIDGE_ServiceHandle_NAME, SHUBAO_BRIDGE_ServiceHandle_VALUE_DOOR_OPEN_DELAY);
        printLogError("The Door is Opened!");
        startService(intent3);
    }

    private void initWebSocketClient() {
        if (null == mWebSocketClient) {
            mWebSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake serverHandshake) {
                    printLogError("onOpen: ");
                    initWSReadyFlag = true;
                }

                @Override
                public void onMessage(String s) {
                    printLogError("onMessage: " + s);
                    // 对收到的消息做处理
                    printLogError("人脸检测成功！");
                    printLogError("你好！" + s);

                    openDoor();
                    printMessgae = "欢迎光临！" + s + "，你好！";
                    handler.sendEmptyMessage(0); // 显示欢迎信息
                    handler.sendEmptyMessageDelayed(1, 5000); // 5秒后清空
                }

                @Override
                public void onClose(int i, String s, boolean b) {
                    printLogError("onClose: ");
                    initWSReadyFlag = false;
                    // 断线重连
                    initWebSocketClient();
                }

                @Override
                public void onError(Exception e) {
                    printLogError("onError: " + e.getLocalizedMessage());
                    initWSReadyFlag = false;
                    // 出现错误，尝试重连?
                    initWebSocketClient();
                }
            };
            mWebSocketClient.connect();
        }
    }

    /**
     * 预览绘制
     */
    private class DrawWorker implements Runnable {

        private int previewFrameCount = 0; // 计数
        private boolean toSend = true;


        private BufferedOutputStream outputStream;
        private String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/test3.jpg";
        // 生成视频文件，保存本地
        private void createfile(){
            File file = new File(path);

            if(file.exists()){
                file.delete();
            }

            try {
                outputStream = new BufferedOutputStream(new FileOutputStream(file));
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            try {
                // 生成图片
                createfile();

                do {
                    boolean hasFrame = false;
                    synchronized (DrawLock) {
                        try {
                            while (!mCameraFrameReady) {
                                printLogError("CameraWorker !mCameraFrameReady("
                                        + mCameraFrameReady
                                        + ")drawLock.wait()");
                                DrawLock.wait();
                            }
                        } catch (InterruptedException e) {
                            printLogError("DrawWorker out:" + e.getLocalizedMessage());
                            return;
                        }
                    }

                    if (mCameraFrameReady) {
                        mChainIdx = 1 - mChainIdx;
                        mCameraFrameReady = false;
                        hasFrame = true;
                    }
                    printLogError("DrawWorker");
                    if (hasFrame) {
                        if (!mCameraFrame[1 - mChainIdx].empty()) {
                            drawFrame(mCameraFrame[1 - mChainIdx]);
                        }
                    }
                    Thread.sleep(40);
                } while (true);
            } catch (Exception e) {
                e.printStackTrace();
                printLogError("DrawWorker out:" + e.getLocalizedMessage());
            }
        }

        /**
         * 绘制帧
         *
         * @param frame
         */
        private void drawFrame(JavaCameraFrame frame) {

//            long start = System.currentTimeMillis();
            printLogError("drawFrame drawFrame");
            Canvas canvas = mSurfaceHolder.lockCanvas();
            if (canvas != null) {
                try {

//                    Mat rgbaMat = frame.rgba();
//                    Utils.matToBitmap(rgbaMat, mCacheBitmap);
//                    // 绘制bitmap
//                    Bitmap bitmap = adjustPhotoRotation(mCacheBitmap,1280,720);
//                    canvas.drawBitmap(bitmap, 0, 0, null);

                    Mat rgbaMat = frame.rgba();
                    Bitmap matTobitmap = Bitmap.createBitmap(cameraWidth, cameraHeight, Bitmap.Config.ARGB_8888);;
                    Utils.matToBitmap(rgbaMat, matTobitmap);
                    // 绘制bitmap
                    Bitmap bitmap = adjustPhotoRotation(matTobitmap,1280,720);
                    bitmap = tailorBitmap(bitmap , 1280 / 5 ,  0 , 1280 / 5 * 4 , 720 );
                    Bitmap bitmapWithRect = addRect( bitmap , 1280 , 720 );
                    canvas.drawBitmap(bitmapWithRect, 0, 0, null);

                    mCacheBitmap = tailorBitmap( bitmap , 1280 / 6 - 10 , 0 , 1280 / 3 , 720 );

                    previewFrameCount++;

                    Integer frameNum = toSend ? kSendFrameNum : kStopFrameNum;
                    if (previewFrameCount == frameNum) {
                        previewFrameCount = 0;
                        toSend = !toSend;
                    }
                    if (toSend) {
                        sendData();
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
                mSurfaceHolder.unlockCanvasAndPost(canvas);
            }

//            long spent = System.currentTimeMillis() - start;
//            printLogError("drawFrame total 耗时：" + spent + "ms");
        }

        private Bitmap tailorBitmap( Bitmap bitmap , int x , int y , int width , int height ){
            return Bitmap.createBitmap( bitmap , x , y , width  , height );
        }
        //添加矩形框
        private Bitmap addRect(Bitmap bitmap , int width , int height ){
            Canvas canvas = new Canvas(bitmap);
            Paint paint = new Paint();
            int left = width / 6 - 10 ;
            int top = 0;
            int right = width / 6 * 3 - 10 ;
            int bottom = height;
            paint.setColor(Color.RED);
            paint.setStyle(Paint.Style.STROKE);//不填充
            paint.setStrokeWidth(10);  //线的宽度
            canvas.drawRect(left, top, right, bottom, paint);
            return bitmap;
        }


        //镜像翻转
        private Bitmap adjustPhotoRotation(Bitmap tempBitmap, int width , int height)
        {
            Matrix m = new Matrix();
            m.setScale(-1,1);
            Bitmap reverseBitmap = Bitmap.createBitmap(tempBitmap,0,0,width,height,m,true);
            return reverseBitmap;
        }
    }


    private void sendData() {
        // 压缩发送
        if (initWSReadyFlag) { //  && previewFrameCount % 6 == 0
            sendDataThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 发送JPEG格式的数据
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        // 压缩率为0.5
                        // 质量压缩方法，这里50表示压缩50%，把压缩后的数据存放到baos中
                        mCacheBitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos);
                        byte[] bytes = baos.toByteArray();

                        printLogError("baos.toByteArray().length: " + baos.toByteArray().length);
                        if (bytes != null) {
                            if (mWebSocketClient != null) {
                                long start = System.currentTimeMillis();
//                                mWebSocketClient.send("" + start);
                                mWebSocketClient.send(bytes);
                                long spent = System.currentTimeMillis() - start;
                                printLogError("Send Video Data total 耗时：" + spent + "ms");
                            } else {
                                initWebSocketClient();
                            }
                        }

                    } catch (Exception e) {
                        printLogError("sendDataThread Out: " + e.getLocalizedMessage());
                        e.printStackTrace();
                    }
                }
            });
            sendDataThread.start();
        }
    }

    /**
     * 图像处理缓存对象
     */
    class JavaCameraFrame {

        private Mat mYuvFrameMat;
        private Mat mFrameRgba;
        private Byte[] data;

        public JavaCameraFrame(int width, int height) {
            super();

            mYuvFrameMat = new Mat(height + (height / 2), width, CvType.CV_8UC1);
            mFrameRgba = new Mat();
        }

        public boolean empty() {
            return mYuvFrameMat.empty();
        }

        public void put(byte[] data) {
            mYuvFrameMat.put(0, 0, data);
        }

        public Mat rgba() {
            Imgproc.cvtColor(mYuvFrameMat, mFrameRgba,
                    Imgproc.COLOR_YUV2RGBA_YV12, 4);
            return mFrameRgba;
        }

        public void release() {
            mYuvFrameMat.release();
            mFrameRgba.release();
        }
    }

}