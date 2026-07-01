#include <jni.h>

#include <android/log.h>

#include <chrono>
#include <sstream>
#include <string>
#include <thread>

#include "liblsl_qcl081_min.h"

namespace {
constexpr const char *kLogTag = "Qcl081LslOutlet";

std::string SafeString(const char *value) {
    return value != nullptr ? std::string(value) : std::string();
}

std::string JStringToStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string JsonEscape(const std::string &value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\':
                out << "\\\\";
                break;
            case '"':
                out << "\\\"";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                out << c;
                break;
        }
    }
    return out.str();
}

jstring NewJsonString(JNIEnv *env, const std::string &json) {
    return env->NewStringUTF(json.c_str());
}

std::string BlockedJson(
    const std::string &streamName,
    const std::string &streamType,
    const std::string &sourceId,
    int sampleCount,
    const std::string &issueCode,
    const std::string &notes
) {
    std::ostringstream json;
    json << "{"
         << "\"status\":\"blocked\","
         << "\"source\":\"quest-runtime\","
         << "\"stream_name\":\"" << JsonEscape(streamName) << "\","
         << "\"stream_type\":\"" << JsonEscape(streamType) << "\","
         << "\"source_id\":\"" << JsonEscape(sourceId) << "\","
         << "\"samples_requested\":" << sampleCount << ","
         << "\"samples_published\":0,"
         << "\"source_timestamps_monotonic\":false,"
         << "\"library_info\":\"" << JsonEscape(SafeString(lsl_library_info())) << "\","
         << "\"last_error\":\"" << JsonEscape(SafeString(lsl_last_error())) << "\","
         << "\"issue_codes\":[\"" << JsonEscape(issueCode) << "\"],"
         << "\"notes\":\"" << JsonEscape(notes) << "\""
         << "}";
    return json.str();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl081LslNativeBridge_nativeLibraryInfo(
    JNIEnv *env,
    jclass
) {
    return env->NewStringUTF(SafeString(lsl_library_info()).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl081LslNativeBridge_nativePublishSamples(
    JNIEnv *env,
    jclass,
    jstring streamNameValue,
    jstring streamTypeValue,
    jstring sourceIdValue,
    jint sampleCountValue,
    jint warmupMsValue,
    jint intervalMsValue
) {
    const std::string streamName = JStringToStdString(env, streamNameValue);
    const std::string streamType = JStringToStdString(env, streamTypeValue);
    const std::string sourceId = JStringToStdString(env, sourceIdValue);
    const int sampleCount = sampleCountValue > 0 ? sampleCountValue : 1;
    const int warmupMs = warmupMsValue > 0 ? warmupMsValue : 0;
    const int intervalMs = intervalMsValue > 0 ? intervalMsValue : 1;

    lsl_streaminfo info = lsl_create_streaminfo(
        streamName.c_str(),
        streamType.c_str(),
        1,
        0.0,
        cft_float32,
        sourceId.c_str()
    );
    if (info == nullptr) {
        return NewJsonString(
            env,
            BlockedJson(
                streamName,
                streamType,
                sourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_streaminfo_create_failed",
                "Quest liblsl streaminfo creation failed."
            )
        );
    }

    lsl_outlet outlet = lsl_create_outlet(info, 1, 60);
    if (outlet == nullptr) {
        lsl_destroy_streaminfo(info);
        return NewJsonString(
            env,
            BlockedJson(
                streamName,
                streamType,
                sourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_outlet_create_failed",
                "Quest liblsl outlet creation failed."
            )
        );
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Publishing %d QCL-081 LSL samples on stream %s source_id=%s",
        sampleCount,
        streamName.c_str(),
        sourceId.c_str()
    );

    std::this_thread::sleep_for(std::chrono::milliseconds(warmupMs));
    int published = 0;
    bool monotonic = true;
    double firstTimestamp = 0.0;
    double lastTimestamp = 0.0;
    for (int sequence = 0; sequence < sampleCount; ++sequence) {
        float sample[1] = {static_cast<float>(sequence)};
        const double timestamp = lsl_local_clock();
        if (published == 0) {
            firstTimestamp = timestamp;
        }
        if (published > 0 && timestamp <= lastTimestamp) {
            monotonic = false;
        }
        lastTimestamp = timestamp;
        const int result = lsl_push_sample_ftp(outlet, sample, timestamp, 1);
        if (result != 0) {
            break;
        }
        ++published;
        std::this_thread::sleep_for(std::chrono::milliseconds(intervalMs));
    }

    lsl_destroy_outlet(outlet);
    lsl_destroy_streaminfo(info);

    const bool passed = published == sampleCount && monotonic;
    std::ostringstream json;
    json << "{"
         << "\"status\":\"" << (passed ? "pass" : published > 0 ? "warn" : "fail") << "\","
         << "\"source\":\"quest-runtime\","
         << "\"stream_name\":\"" << JsonEscape(streamName) << "\","
         << "\"stream_type\":\"" << JsonEscape(streamType) << "\","
         << "\"source_id\":\"" << JsonEscape(sourceId) << "\","
         << "\"samples_requested\":" << sampleCount << ","
         << "\"samples_published\":" << published << ","
         << "\"source_timestamp_domain\":\"lsl_local_clock\","
         << "\"source_timestamp_first_seconds\":" << firstTimestamp << ","
         << "\"source_timestamp_last_seconds\":" << lastTimestamp << ","
         << "\"source_timestamps_monotonic\":" << (monotonic ? "true" : "false") << ","
         << "\"library_info\":\"" << JsonEscape(SafeString(lsl_library_info())) << "\","
         << "\"issue_codes\":[";
    if (!passed) {
        json << "\"rusty.quest.issue.qcl081_lsl_publish_incomplete\"";
    }
    json << "],"
         << "\"notes\":\"Quest-owned liblsl outlet published source-timestamped float32 sequence samples.\""
         << "}";

    return NewJsonString(env, json.str());
}
