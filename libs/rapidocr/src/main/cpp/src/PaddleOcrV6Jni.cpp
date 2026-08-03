#include "OcrResultUtils.h"
#include "BitmapUtils.h"
#include "OcrLite.h"
#include "OcrUtils.h"

#include <cstdlib>
#include <memory>
#include <mutex>

namespace {

std::unique_ptr<OcrLite> paddleOcrLite;
std::mutex paddleOcrMutex;

OcrLite *getPaddleOcrLite() {
    std::lock_guard<std::mutex> guard(paddleOcrMutex);
    if (!paddleOcrLite) {
        paddleOcrLite = std::make_unique<OcrLite>();
    }
    return paddleOcrLite.get();
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_benjaminwan_ocrlibrary_PaddleOcrV6Engine_initNative(
        JNIEnv *env,
        jobject,
        jobject assetManager,
        jstring openCvPluginDir,
        jint numThread,
        jstring detName,
        jstring recName,
        jstring keysName) {
    // OpenCV 4.5.3 otherwise probes optional parallel plugins beside the native
    // library. On modern Android that path points inside "base.apk!/lib/..."
    // and the old plugin loader cannot enumerate it. Select the OpenMP backend
    // already linked into this module before the first OpenCV API call.
    const std::string pluginDir = jstringTostring(env, openCvPluginDir);
    setenv("OPENCV_CORE_PLUGIN_PATH", pluginDir.c_str(), 1);
    setenv("OPENCV_PARALLEL_BACKEND", "OPENMP", 1);
    cv::setNumThreads(numThread > 0 ? numThread : 1);
    auto *engine = getPaddleOcrLite();
    engine->init(
            env,
            assetManager,
            numThread,
            jstringTostring(env, detName),
            "",
            jstringTostring(env, recName),
            jstringTostring(env, keysName));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_benjaminwan_ocrlibrary_PaddleOcrV6Engine_detectNative(
        JNIEnv *env,
        jobject,
        jobject input,
        jobject output,
        jint maxSideLen,
        jfloat boxScoreThresh,
        jfloat boxThresh,
        jfloat unClipRatio) {
    std::lock_guard<std::mutex> guard(paddleOcrMutex);
    auto *engine = paddleOcrLite.get();
    if (engine == nullptr) {
        jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(exceptionClass, "PP-OCRv6 engine is not initialized");
        return nullptr;
    }

    cv::Mat imageRgba;
    cv::Mat imageBgr;
    bitmapToMat(env, input, imageRgba);
    cv::cvtColor(imageRgba, imageBgr, cv::COLOR_RGBA2BGR);

    const int sourceLongSide = (std::max)(imageBgr.cols, imageBgr.rows);
    const int targetLongSide = maxSideLen <= 0
            ? (std::min)(sourceLongSide, 960)
            : (std::min)(sourceLongSide, int(maxSideLen));
    ScaleParam scale = getScaleParam(imageBgr, targetLongSide);
    cv::Rect sourceRect(0, 0, imageBgr.cols, imageBgr.rows);

    OcrResult result = engine->detect(
            imageBgr,
            sourceRect,
            scale,
            boxScoreThresh,
            boxThresh,
            unClipRatio,
            false,
            false);

    return OcrResultUtils(env, result, output).getJObject();
}
