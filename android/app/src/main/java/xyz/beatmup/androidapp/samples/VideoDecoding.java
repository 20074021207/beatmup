/*
    Beatmup image and signal processing library
    Copyright (C) 2020, lnstadrum

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package xyz.beatmup.androidapp.samples;

import android.app.Activity;

import java.io.File;
import java.io.IOException;

import Beatmup.Android.Camera;
import Beatmup.Android.Decoder;
import Beatmup.Bitmap;
import Beatmup.Context;
import Beatmup.Geometry.AffineMapping;
import Beatmup.Imaging.PixelFormat;
import Beatmup.Imaging.Resampler;
import Beatmup.Pipelining.Multitask;
import Beatmup.Pipelining.TaskHolder;
import Beatmup.Rendering.Scene;
import Beatmup.Shading.ImageShader;
import Beatmup.Shading.ShaderApplicator;
import Beatmup.Task;
import xyz.beatmup.androidapp.MainActivity;

public class VideoDecoding extends TestSample {
    private Decoder decoder1, decoder2;
    private MainActivity activity;
    private Resampler resampler;
    private ShaderApplicator textureCopy;
    private Multitask multitask;
    private TaskHolder resamplerTaskHolder;
    private Bitmap inputFrame, outputFrame;
    private String info = "";

    public VideoDecoding(MainActivity activity) {
        this.activity = activity;
    }

    @Override
    public String getCaption() {
        return "Video decoding";
    }

    @Override
    public String getDescription() {
        return "Video decoding with super-resolution on second track.";
    }

    @Override
    public String usesExternalFile() {
        return "video/*";
    }

    @Override
    public Scene designScene(final Task drawingTask, Activity app, Camera camera, String extFile) throws IOException {
        System.out.println("VideoDecoding: Starting designScene with file: " + extFile);
        
        try {
            // 检查文件是否存在
            File videoFile = new File(extFile);
            System.out.println("VideoDecoding: Checking file existence: " + videoFile.getAbsolutePath());
            
            if (!videoFile.exists()) {
                System.err.println("VideoDecoding: File does not exist: " + extFile);
                throw new IOException("Video file does not exist: " + extFile);
            }
            if (!videoFile.canRead()) {
                System.err.println("VideoDecoding: Cannot read file: " + extFile);
                throw new IOException("Cannot read video file: " + extFile);
            }

            System.out.println("VideoDecoding: File OK, getting context");
            Context context = drawingTask.getContext();

            // 初始化第一个解码器（原始视频）
            System.out.println("VideoDecoding: Creating first decoder");
            decoder1 = new Decoder(context);
            decoder1.open(videoFile);
            Decoder.VideoTrack videoTrack1 = decoder1.selectDefaultVideoTrack();
            if (videoTrack1 == null) {
                System.err.println("VideoDecoding: No video track found in first decoder");
                throw new IOException("No video track found in file: " + extFile);
            }
            videoTrack1.changeBlockingPolicy(Decoder.BlockingPolicy.PLAYBACK);
            System.out.println("VideoDecoding: First decoder OK");

            // 初始化第二个解码器（用于超分）
            System.out.println("VideoDecoding: Creating second decoder");
            decoder2 = new Decoder(context);
            decoder2.open(videoFile);
            Decoder.VideoTrack videoTrack2 = decoder2.selectDefaultVideoTrack();
            if (videoTrack2 == null) {
                System.err.println("VideoDecoding: No video track found in second decoder");
                throw new IOException("No video track found in second decoder");
            }
            videoTrack2.changeBlockingPolicy(Decoder.BlockingPolicy.PLAYBACK);
            System.out.println("VideoDecoding: Second decoder OK");

            // 获取视频实际尺寸
            System.out.println("VideoDecoding: Starting decoders to get frame size");
            decoder1.play();
            decoder2.play();
            
            // 等待解码器准备好
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // 获取实际的视频帧尺寸
            Beatmup.Bitmap frame1 = videoTrack1.getFrame();
            Beatmup.Bitmap frame2 = videoTrack2.getFrame();
            
            int width, height;
            if (frame2 != null) {
                width = frame2.getWidth();
                height = frame2.getHeight();
                System.out.println("VideoDecoding: Got actual video size: " + width + "x" + height);
            } else {
                // 使用默认尺寸
                width = 640;
                height = 480;
                System.out.println("VideoDecoding: Using default size: " + width + "x" + height);
            }
            
            int scale = 2;
            
            // 创建中间帧和输出帧用于超分
            inputFrame = new Beatmup.Bitmap(context, width, height, PixelFormat.TripleByte);
            outputFrame = new Beatmup.Bitmap(context, scale * width, scale * height, PixelFormat.TripleByte);
            info = String.format("%dx%d -> %dx%d", width, height, scale * width, scale * height);
            
            System.out.println("VideoDecoding: Created super-resolution bitmaps: " + info);

            // 创建纹理复制着色器（将视频帧复制到中间纹理）
            System.out.println("VideoDecoding: Setting up texture copy shader");
            textureCopy = new ShaderApplicator(context);
            textureCopy.addSampler(videoTrack2.getFrame());
            textureCopy.setOutput(inputFrame);
            textureCopy.setShader(new ImageShader(context));
            textureCopy.getShader().setSourceCode(
                    "uniform beatmupSampler image;" +
                    "varying highp vec2 texCoord;" +
                    "void main() {" +
                    " gl_FragColor = beatmupTexture(image, texCoord);" +
                    "}"
            );

            // 初始化CNN超分辨率重采样器
            System.out.println("VideoDecoding: Setting up CNN resampler");
            resampler = new Resampler(context);
            resampler.setMode(Resampler.Mode.CONVNET);
            resampler.setInput(inputFrame);
            resampler.setOutput(outputFrame);

            // 创建多任务管道
            System.out.println("VideoDecoding: Setting up multitask pipeline");
            multitask = new Multitask(context);
            multitask.addTask(textureCopy, Multitask.RepetitionPolicy.REPEAT_ALWAYS);
            resamplerTaskHolder = multitask.addTask(resampler, Multitask.RepetitionPolicy.REPEAT_ALWAYS);
            multitask.addTask(drawingTask, Multitask.RepetitionPolicy.REPEAT_ALWAYS);
            multitask.measure();
            
            System.out.println("VideoDecoding: Super-resolution pipeline ready");

            // 创建场景
            System.out.println("VideoDecoding: Creating scene");
            Scene scene = new Scene();
            
            // 第一层：原始视频（上半部分）
            Scene.BitmapLayer layer1 = scene.newBitmapLayer();
            layer1.setBitmap(videoTrack1.getFrame());
            layer1.scale(0.5f);
            layer1.setCenterPosition(0.25f, 0.5f);

            // 第二层：超分视频（下半部分）
            Scene.BitmapLayer layer2 = scene.newBitmapLayer();
            layer2.setBitmap(outputFrame);  // 使用超分后的输出帧
            layer2.scale(0.5f);
            layer2.setCenterPosition(0.75f, 0.5f);

            // 解码器已经在上面启动了
            System.out.println("VideoDecoding: Decoders already started");

            System.out.println("VideoDecoding: Scene created successfully");
            return scene;

        } catch (Exception e) {
            // 清理资源
            cleanup();
            throw new IOException("Failed to initialize video decoding with super-resolution: " + e.getMessage(), e);
        }
    }

    @Override
    public Task getDrawingTask() {
        return multitask;  // 返回多任务管道
    }

    @Override
    public String getRuntimeInfo() {
        if (resamplerTaskHolder != null)
            return String.format("%s, %.2f FPS", info, 1000 / resamplerTaskHolder.getRunTime());
        else
            return info;
    }

    /**
     * 清理资源
     */
    private void cleanup() {
        System.out.println("VideoDecoding: Cleaning up resources");
        
        if (decoder1 != null) {
            try { 
                decoder1.stop();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error stopping decoder1: " + e.getMessage());
            }
            decoder1 = null;
        }
        if (decoder2 != null) {
            try { 
                decoder2.stop();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error stopping decoder2: " + e.getMessage());
            }
            decoder2 = null;
        }
        if (inputFrame != null) {
            try { 
                inputFrame.dispose();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error disposing inputFrame: " + e.getMessage());
            }
            inputFrame = null;
        }
        if (outputFrame != null) {
            try { 
                outputFrame.dispose();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error disposing outputFrame: " + e.getMessage());
            }
            outputFrame = null;
        }
        if (textureCopy != null) {
            try { 
                textureCopy.dispose();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error disposing textureCopy: " + e.getMessage());
            }
            textureCopy = null;
        }
        if (resampler != null) {
            try { 
                resampler.dispose();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error disposing resampler: " + e.getMessage());
            }
            resampler = null;
        }
        if (multitask != null) {
            try { 
                multitask.dispose();
            } catch (Exception e) {
                System.err.println("VideoDecoding: Error disposing multitask: " + e.getMessage());
            }
            multitask = null;
        }
        resamplerTaskHolder = null;
        
        System.out.println("VideoDecoding: Cleanup completed");
    }

    /**
     * 在示例结束时清理资源
     */
    @Override
    public void stop() {
        cleanup();
        super.stop(); // TestSample 基类有 stop() 方法
    }
}
