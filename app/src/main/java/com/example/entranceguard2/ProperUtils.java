package com.example.entranceguard2;

/**
 * Created by 风轻云淡 on 2018/7/23.
 */

import android.content.Context;

import java.io.InputStream;
import java.util.Properties;

public class ProperUtils {
    private static Properties properties;

    public static Properties getProperties(Context c){
        Properties props = new Properties();
        try {
            //方法一：通过activity中的context攻取setting.properties的FileInputStream
            InputStream in = c.getAssets().open("appConfig.properties");
            //方法二：通过class获取setting.properties的FileInputStream
            props.load(in);
        } catch (Exception e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        properties = props;

        return properties;
    }
}
