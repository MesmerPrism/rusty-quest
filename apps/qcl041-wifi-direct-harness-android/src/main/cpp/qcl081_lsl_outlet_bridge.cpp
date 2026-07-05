#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <dlfcn.h>
#include <cmath>
#include <iomanip>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

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

std::string XmlValue(const std::string &xml, const std::string &tag) {
    const std::string openTag = "<" + tag + ">";
    const std::string closeTag = "</" + tag + ">";
    const size_t start = xml.find(openTag);
    if (start == std::string::npos) {
        return {};
    }
    const size_t valueStart = start + openTag.size();
    const size_t end = xml.find(closeTag, valueStart);
    if (end == std::string::npos || end < valueStart) {
        return {};
    }
    return xml.substr(valueStart, end - valueStart);
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

struct EchoSample {
    int sequence;
    double hostSendClockSeconds;
    double commandCaptureTimestampSeconds;
    double questReceiveClockSeconds;
    double questEchoSendClockSeconds;
    double questProcessingMs;
    double nativeHostToQuestMs;
    bool hasNativeHostToQuestMs;
};

void AppendNumberOrNull(std::ostringstream &json, double value) {
    if (std::isfinite(value)) {
        json << std::setprecision(15) << value;
    } else {
        json << "null";
    }
}

void AppendEchoSampleObject(std::ostringstream &json, const EchoSample &sample) {
    json << "{"
         << "\"sequence\":" << sample.sequence << ","
         << "\"host_send_lsl_clock_seconds\":";
    AppendNumberOrNull(json, sample.hostSendClockSeconds);
    json << ",\"command_capture_timestamp_seconds\":";
    AppendNumberOrNull(json, sample.commandCaptureTimestampSeconds);
    json << ",\"quest_receive_lsl_clock_seconds\":";
    AppendNumberOrNull(json, sample.questReceiveClockSeconds);
    json << ",\"quest_echo_send_lsl_clock_seconds\":";
    AppendNumberOrNull(json, sample.questEchoSendClockSeconds);
    json << ",\"quest_processing_ms\":";
    AppendNumberOrNull(json, sample.questProcessingMs);
    json << ",\"native_host_to_quest_ms\":";
    AppendNumberOrNull(json, sample.hasNativeHostToQuestMs ? sample.nativeHostToQuestMs : NAN);
    json << "}";
}

double Percentile(std::vector<double> values, double percentile) {
    if (values.empty()) {
        return NAN;
    }
    std::sort(values.begin(), values.end());
    const double clamped = std::max(0.0, std::min(100.0, percentile));
    const double rank = (clamped / 100.0) * static_cast<double>(values.size() - 1);
    const size_t index = static_cast<size_t>(std::round(rank));
    return values[std::min(index, values.size() - 1)];
}

void AppendStatsObject(std::ostringstream &json, const std::vector<double> &values) {
    if (values.empty()) {
        json << "null";
        return;
    }
    std::vector<double> sorted = values;
    std::sort(sorted.begin(), sorted.end());
    json << "{"
         << "\"min_ms\":";
    AppendNumberOrNull(json, sorted.front());
    json << ",\"median_ms\":";
    AppendNumberOrNull(json, Percentile(values, 50.0));
    json << ",\"p95_ms\":";
    AppendNumberOrNull(json, Percentile(values, 95.0));
    json << ",\"max_ms\":";
    AppendNumberOrNull(json, sorted.back());
    json << "}";
}

double ReadTimeCorrection(lsl_inlet inlet, double timeoutSeconds, bool *available, int32_t *errorCode) {
    int32_t ec = 0;
    const double value = lsl_time_correction(inlet, timeoutSeconds, &ec);
    if (errorCode != nullptr) {
        *errorCode = ec;
    }
    if (available != nullptr) {
        *available = (ec == 0 && std::isfinite(value));
    }
    return value;
}

std::string BlockedEchoJson(
    const std::string &commandStreamName,
    const std::string &commandStreamType,
    const std::string &commandSourceId,
    const std::string &echoStreamName,
    const std::string &echoStreamType,
    const std::string &echoSourceId,
    int sampleCount,
    const std::string &issueCode,
    const std::string &notes
) {
    std::ostringstream json;
    json << "{"
         << "\"schema\":\"rusty.quest.qcl081_lsl_echo_roundtrip.v1\","
         << "\"status\":\"blocked\","
         << "\"source\":\"quest-runtime\","
         << "\"command_stream_name\":\"" << JsonEscape(commandStreamName) << "\","
         << "\"command_stream_type\":\"" << JsonEscape(commandStreamType) << "\","
         << "\"command_source_id\":\"" << JsonEscape(commandSourceId) << "\","
         << "\"echo_stream_name\":\"" << JsonEscape(echoStreamName) << "\","
         << "\"echo_stream_type\":\"" << JsonEscape(echoStreamType) << "\","
         << "\"echo_source_id\":\"" << JsonEscape(echoSourceId) << "\","
         << "\"samples_requested\":" << sampleCount << ","
         << "\"command_samples_received\":0,"
         << "\"echo_samples_published\":0,"
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

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl081LslNativeBridge_nativeSetConfigContent(
    JNIEnv *env,
    jclass,
    jstring configContentValue
) {
    const std::string configContent = JStringToStdString(env, configContentValue);
    using SetConfigContentFn = void (*)(const char *);
    auto *symbol = dlsym(RTLD_DEFAULT, "lsl_set_config_content");
    if (symbol == nullptr) {
        return JNI_FALSE;
    }
    reinterpret_cast<SetConfigContentFn>(symbol)(configContent.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl081LslNativeBridge_nativeSetConfigPath(
    JNIEnv *env,
    jclass,
    jstring configPathValue
) {
    const std::string configPath = JStringToStdString(env, configPathValue);
    if (configPath.empty()) {
        return JNI_FALSE;
    }
    return setenv("LSLAPICFG", configPath.c_str(), 1) == 0 ? JNI_TRUE : JNI_FALSE;
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

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_mesmerprism_rustyquest_qcl041_Qcl081LslNativeBridge_nativeEchoRoundTrip(
    JNIEnv *env,
    jclass,
    jstring commandStreamNameValue,
    jstring commandStreamTypeValue,
    jstring commandSourceIdValue,
    jstring echoStreamNameValue,
    jstring echoStreamTypeValue,
    jstring echoSourceIdValue,
    jint sampleCountValue,
    jint warmupMsValue,
    jint outletHoldAfterMsValue,
    jint timeoutSecondsValue
) {
    const std::string commandStreamName = JStringToStdString(env, commandStreamNameValue);
    const std::string commandStreamType = JStringToStdString(env, commandStreamTypeValue);
    const std::string commandSourceId = JStringToStdString(env, commandSourceIdValue);
    const std::string echoStreamName = JStringToStdString(env, echoStreamNameValue);
    const std::string echoStreamType = JStringToStdString(env, echoStreamTypeValue);
    const std::string echoSourceId = JStringToStdString(env, echoSourceIdValue);
    const int sampleCount = sampleCountValue > 0 ? sampleCountValue : 1;
    const int warmupMs = warmupMsValue > 0 ? warmupMsValue : 0;
    const int outletHoldAfterMs = outletHoldAfterMsValue > 0 ? outletHoldAfterMsValue : 0;
    const int timeoutSeconds = timeoutSecondsValue > 0 ? timeoutSecondsValue : 30;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "Starting QCL-081 LSL echo command_source_id=%s echo_source_id=%s samples=%d",
        commandSourceId.c_str(),
        echoSourceId.c_str(),
        sampleCount
    );

    lsl_streaminfo echoInfo = lsl_create_streaminfo(
        echoStreamName.c_str(),
        echoStreamType.c_str(),
        5,
        0.0,
        cft_double64,
        echoSourceId.c_str()
    );
    if (echoInfo == nullptr) {
        return NewJsonString(
            env,
            BlockedEchoJson(
                commandStreamName,
                commandStreamType,
                commandSourceId,
                echoStreamName,
                echoStreamType,
                echoSourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_echo_streaminfo_create_failed",
                "Quest liblsl streaminfo creation failed for the echo outlet."
            )
        );
    }

    lsl_outlet echoOutlet = lsl_create_outlet(echoInfo, 1, 60);
    if (echoOutlet == nullptr) {
        lsl_destroy_streaminfo(echoInfo);
        return NewJsonString(
            env,
            BlockedEchoJson(
                commandStreamName,
                commandStreamType,
                commandSourceId,
                echoStreamName,
                echoStreamType,
                echoSourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_echo_outlet_create_failed",
                "Quest liblsl outlet creation failed for the echo stream."
            )
        );
    }

    lsl_streaminfo resolvedCommand[1] = {nullptr};
    const int32_t resolved = lsl_resolve_byprop(
        resolvedCommand,
        1,
        "source_id",
        commandSourceId.c_str(),
        1,
        static_cast<double>(timeoutSeconds)
    );
    if (resolved <= 0 || resolvedCommand[0] == nullptr) {
        lsl_destroy_outlet(echoOutlet);
        lsl_destroy_streaminfo(echoInfo);
        return NewJsonString(
            env,
            BlockedEchoJson(
                commandStreamName,
                commandStreamType,
                commandSourceId,
                echoStreamName,
                echoStreamType,
                echoSourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_command_stream_resolve_failed",
                "Quest liblsl inlet did not resolve the Windows command stream by source_id."
            )
        );
    }

    const std::string resolvedCommandXml = SafeString(lsl_get_xml(resolvedCommand[0]));
    const std::string resolvedCommandHostname = SafeString(lsl_get_hostname(resolvedCommand[0]));
    const std::string resolvedCommandUid = SafeString(lsl_get_uid(resolvedCommand[0]));
    const std::string resolvedCommandSessionId = SafeString(lsl_get_session_id(resolvedCommand[0]));
    const double resolvedCommandCreatedAt = lsl_get_created_at(resolvedCommand[0]);
    const std::string resolvedCommandV4Address = XmlValue(resolvedCommandXml, "v4address");
    const std::string resolvedCommandV4DataPort = XmlValue(resolvedCommandXml, "v4data_port");
    const std::string resolvedCommandV4ServicePort = XmlValue(resolvedCommandXml, "v4service_port");
    const std::string resolvedCommandV6Address = XmlValue(resolvedCommandXml, "v6address");
    const std::string resolvedCommandV6DataPort = XmlValue(resolvedCommandXml, "v6data_port");
    const std::string resolvedCommandV6ServicePort = XmlValue(resolvedCommandXml, "v6service_port");

    constexpr int kCommandInletMaxBuffer = 60;
    lsl_inlet commandInlet = lsl_create_inlet(resolvedCommand[0], kCommandInletMaxBuffer, 1, 1);
    lsl_destroy_streaminfo(resolvedCommand[0]);
    if (commandInlet == nullptr) {
        lsl_destroy_outlet(echoOutlet);
        lsl_destroy_streaminfo(echoInfo);
        return NewJsonString(
            env,
            BlockedEchoJson(
                commandStreamName,
                commandStreamType,
                commandSourceId,
                echoStreamName,
                echoStreamType,
                echoSourceId,
                sampleCount,
                "rusty.quest.issue.qcl081_lsl_command_inlet_create_failed",
                "Quest liblsl inlet creation failed for the Windows command stream."
            )
        );
    }

    int32_t openError = 0;
    lsl_open_stream(commandInlet, std::min(5.0, static_cast<double>(timeoutSeconds)), &openError);
    bool commandCorrectionBeforeAvailable = false;
    int32_t commandCorrectionBeforeError = 0;
    const double commandCorrectionBefore = ReadTimeCorrection(
        commandInlet,
        1.0,
        &commandCorrectionBeforeAvailable,
        &commandCorrectionBeforeError
    );

    std::this_thread::sleep_for(std::chrono::milliseconds(warmupMs));

    const auto loopStart = std::chrono::steady_clock::now();
    const auto deadline = loopStart + std::chrono::seconds(timeoutSeconds);
    std::vector<EchoSample> samples;
    std::vector<double> processingMsValues;
    std::vector<double> nativeHostToQuestMsValues;
    int commandSamplesReceived = 0;
    int echoSamplesPublished = 0;
    bool monotonicSequences = true;
    int previousSequence = -1;
    int firstSequence = -1;
    int lastSequence = -1;
    int duplicateOrReorderedCount = 0;
    int sequenceGapCount = 0;
    int sequenceMissingBetweenFirstLast = 0;
    int largestSequenceGap = 0;
    int pullTimeoutCount = 0;
    int pullTimeoutsAfterFirstSample = 0;
    int consecutivePullTimeoutsAfterLastSample = 0;
    int maxConsecutivePullTimeoutsAfterLastSample = 0;
    bool pushFailed = false;
    bool pullFailed = false;
    int32_t lastPullError = 0;
    int32_t lastPushError = 0;
    bool sawFirstReceiveSteady = false;
    std::chrono::steady_clock::time_point firstReceiveSteady{};
    std::chrono::steady_clock::time_point lastReceiveSteady{};

    while (commandSamplesReceived < sampleCount && std::chrono::steady_clock::now() < deadline) {
        double commandSample[2] = {0.0, 0.0};
        int32_t pullError = 0;
        const double commandCaptureTimestamp = lsl_pull_sample_d(
            commandInlet,
            commandSample,
            2,
            0.25,
            &pullError
        );
        const double questReceiveClock = lsl_local_clock();
        if (pullError != 0) {
            pullFailed = true;
            lastPullError = pullError;
            break;
        }
        if (commandCaptureTimestamp == 0.0) {
            ++pullTimeoutCount;
            if (commandSamplesReceived > 0) {
                ++pullTimeoutsAfterFirstSample;
                ++consecutivePullTimeoutsAfterLastSample;
                maxConsecutivePullTimeoutsAfterLastSample = std::max(
                    maxConsecutivePullTimeoutsAfterLastSample,
                    consecutivePullTimeoutsAfterLastSample
                );
            }
            continue;
        }
        const auto receiveSteady = std::chrono::steady_clock::now();
        if (!sawFirstReceiveSteady) {
            firstReceiveSteady = receiveSteady;
            sawFirstReceiveSteady = true;
        }
        lastReceiveSteady = receiveSteady;
        consecutivePullTimeoutsAfterLastSample = 0;

        const int sequence = static_cast<int>(std::llround(commandSample[0]));
        const double hostSendClock = commandSample[1];
        if (previousSequence >= 0 && sequence <= previousSequence) {
            monotonicSequences = false;
            ++duplicateOrReorderedCount;
        }
        if (previousSequence >= 0 && sequence > previousSequence + 1) {
            const int gap = sequence - previousSequence - 1;
            ++sequenceGapCount;
            sequenceMissingBetweenFirstLast += gap;
            largestSequenceGap = std::max(largestSequenceGap, gap);
        }
        if (firstSequence < 0) {
            firstSequence = sequence;
        }
        previousSequence = sequence;
        lastSequence = sequence;
        ++commandSamplesReceived;

        const double questEchoSendClock = lsl_local_clock();
        const double echoSample[5] = {
            static_cast<double>(sequence),
            hostSendClock,
            questReceiveClock,
            questEchoSendClock,
            commandCaptureTimestamp
        };
        lastPushError = lsl_push_sample_dtp(echoOutlet, echoSample, questEchoSendClock, 1);
        if (lastPushError != 0) {
            pushFailed = true;
            break;
        }
        ++echoSamplesPublished;

        const double processingMs = (questEchoSendClock - questReceiveClock) * 1000.0;
        const bool hasNativeHostToQuest = commandCorrectionBeforeAvailable;
        const double nativeHostToQuestMs = hasNativeHostToQuest
            ? (questReceiveClock - (hostSendClock + commandCorrectionBefore)) * 1000.0
            : NAN;
        samples.push_back(EchoSample{
            sequence,
            hostSendClock,
            commandCaptureTimestamp,
            questReceiveClock,
            questEchoSendClock,
            processingMs,
            nativeHostToQuestMs,
            hasNativeHostToQuest
        });
        processingMsValues.push_back(processingMs);
        if (hasNativeHostToQuest) {
            nativeHostToQuestMsValues.push_back(nativeHostToQuestMs);
        }
    }
    const auto loopEnd = std::chrono::steady_clock::now();
    const bool deadlineExpired = commandSamplesReceived < sampleCount
        && !pullFailed
        && !pushFailed
        && loopEnd >= deadline;
    const char *loopExitReason = "samples_completed";
    if (pullFailed) {
        loopExitReason = "pull_failed";
    } else if (pushFailed) {
        loopExitReason = "push_failed";
    } else if (deadlineExpired) {
        loopExitReason = "deadline_expired";
    } else if (commandSamplesReceived < sampleCount) {
        loopExitReason = "stopped_before_sample_count";
    }
    const double loopElapsedMs = std::chrono::duration<double, std::milli>(loopEnd - loopStart).count();
    const double firstReceiveOffsetMs = sawFirstReceiveSteady
        ? std::chrono::duration<double, std::milli>(firstReceiveSteady - loopStart).count()
        : NAN;
    const double lastReceiveOffsetMs = sawFirstReceiveSteady
        ? std::chrono::duration<double, std::milli>(lastReceiveSteady - loopStart).count()
        : NAN;
    const double lastReceiveAgeAtExitMs = sawFirstReceiveSteady
        ? std::chrono::duration<double, std::milli>(loopEnd - lastReceiveSteady).count()
        : NAN;
    const int expectedSequenceSpan = firstSequence >= 0 && lastSequence >= firstSequence
        ? (lastSequence - firstSequence + 1)
        : 0;
    const double receivedSequenceCoveragePercent = expectedSequenceSpan > 0
        ? (static_cast<double>(commandSamplesReceived) / static_cast<double>(expectedSequenceSpan)) * 100.0
        : NAN;
    if (echoSamplesPublished > 0 && outletHoldAfterMs > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(outletHoldAfterMs));
    }

    bool commandCorrectionAfterAvailable = false;
    int32_t commandCorrectionAfterError = 0;
    const double commandCorrectionAfter = ReadTimeCorrection(
        commandInlet,
        1.0,
        &commandCorrectionAfterAvailable,
        &commandCorrectionAfterError
    );

    lsl_destroy_outlet(echoOutlet);
    lsl_destroy_streaminfo(echoInfo);
    lsl_destroy_inlet(commandInlet);

    const bool passed = commandSamplesReceived == sampleCount
        && echoSamplesPublished == sampleCount
        && monotonicSequences
        && !pullFailed
        && !pushFailed;
    const char *status = passed
        ? "pass"
        : (echoSamplesPublished > 0 ? "warn" : "fail");

    std::ostringstream json;
    json << std::setprecision(15);
    json << "{"
         << "\"schema\":\"rusty.quest.qcl081_lsl_echo_roundtrip.v1\","
         << "\"status\":\"" << status << "\","
         << "\"source\":\"quest-runtime\","
         << "\"command_stream_name\":\"" << JsonEscape(commandStreamName) << "\","
         << "\"command_stream_type\":\"" << JsonEscape(commandStreamType) << "\","
         << "\"command_source_id\":\"" << JsonEscape(commandSourceId) << "\","
         << "\"echo_stream_name\":\"" << JsonEscape(echoStreamName) << "\","
         << "\"echo_stream_type\":\"" << JsonEscape(echoStreamType) << "\","
         << "\"echo_source_id\":\"" << JsonEscape(echoSourceId) << "\","
         << "\"samples_requested\":" << sampleCount << ","
         << "\"command_samples_received\":" << commandSamplesReceived << ","
         << "\"echo_samples_published\":" << echoSamplesPublished << ","
         << "\"warmup_ms\":" << warmupMs << ","
         << "\"outlet_hold_after_ms\":" << outletHoldAfterMs << ","
         << "\"timeout_seconds\":" << timeoutSeconds << ","
         << "\"command_inlet_max_buffer\":" << kCommandInletMaxBuffer << ","
         << "\"resolved_command_stream\":{"
         << "\"hostname\":\"" << JsonEscape(resolvedCommandHostname) << "\","
         << "\"uid\":\"" << JsonEscape(resolvedCommandUid) << "\","
         << "\"session_id\":\"" << JsonEscape(resolvedCommandSessionId) << "\","
         << "\"created_at\":";
    AppendNumberOrNull(json, resolvedCommandCreatedAt);
    json << ",\"v4address\":\"" << JsonEscape(resolvedCommandV4Address) << "\","
         << "\"v4data_port\":\"" << JsonEscape(resolvedCommandV4DataPort) << "\","
         << "\"v4service_port\":\"" << JsonEscape(resolvedCommandV4ServicePort) << "\","
         << "\"v6address\":\"" << JsonEscape(resolvedCommandV6Address) << "\","
         << "\"v6data_port\":\"" << JsonEscape(resolvedCommandV6DataPort) << "\","
         << "\"v6service_port\":\"" << JsonEscape(resolvedCommandV6ServicePort) << "\","
         << "\"xml\":\"" << JsonEscape(resolvedCommandXml) << "\""
         << "},"
         << "\"open_stream_error_code\":" << openError << ","
         << "\"command_time_correction_seconds_before\":";
    AppendNumberOrNull(json, commandCorrectionBeforeAvailable ? commandCorrectionBefore : NAN);
    json << ",\"command_time_correction_seconds_after\":";
    AppendNumberOrNull(json, commandCorrectionAfterAvailable ? commandCorrectionAfter : NAN);
    json << ",\"command_time_correction_error_code_before\":" << commandCorrectionBeforeError
         << ",\"command_time_correction_error_code_after\":" << commandCorrectionAfterError
         << ",\"monotonic_sequences\":" << (monotonicSequences ? "true" : "false")
         << ",\"loop_exit_reason\":\"" << loopExitReason << "\""
         << ",\"deadline_expired\":" << (deadlineExpired ? "true" : "false")
         << ",\"loop_elapsed_ms\":";
    AppendNumberOrNull(json, loopElapsedMs);
    json << ",\"first_sequence\":";
    if (firstSequence >= 0) {
        json << firstSequence;
    } else {
        json << "null";
    }
    json << ",\"last_sequence\":";
    if (lastSequence >= 0) {
        json << lastSequence;
    } else {
        json << "null";
    }
    json << ",\"expected_sequence_span\":" << expectedSequenceSpan
         << ",\"received_sequence_coverage_percent\":";
    AppendNumberOrNull(json, receivedSequenceCoveragePercent);
    json << ",\"sequence_gap_count\":" << sequenceGapCount
         << ",\"sequence_missing_between_first_last\":" << sequenceMissingBetweenFirstLast
         << ",\"largest_sequence_gap\":" << largestSequenceGap
         << ",\"duplicate_or_reordered_count\":" << duplicateOrReorderedCount
         << ",\"pull_timeout_count\":" << pullTimeoutCount
         << ",\"pull_timeouts_after_first_sample\":" << pullTimeoutsAfterFirstSample
         << ",\"max_consecutive_pull_timeouts_after_last_sample\":"
         << maxConsecutivePullTimeoutsAfterLastSample
         << ",\"first_receive_offset_ms\":";
    AppendNumberOrNull(json, firstReceiveOffsetMs);
    json << ",\"last_receive_offset_ms\":";
    AppendNumberOrNull(json, lastReceiveOffsetMs);
    json << ",\"last_receive_age_at_exit_ms\":";
    AppendNumberOrNull(json, lastReceiveAgeAtExitMs);
    json << ",\"last_lsl_error\":\"" << JsonEscape(SafeString(lsl_last_error())) << "\""
         << ",\"quest_processing_ms_summary\":";
    AppendStatsObject(json, processingMsValues);
    json << ",\"native_host_to_quest_ms_summary\":";
    AppendStatsObject(json, nativeHostToQuestMsValues);
    json << ",\"timing_samples\":[";
    const size_t sampleLimit = std::min<size_t>(samples.size(), 50);
    for (size_t index = 0; index < sampleLimit; ++index) {
        if (index > 0) {
            json << ",";
        }
        AppendEchoSampleObject(json, samples[index]);
    }
    json << "],"
         << "\"timing_samples_tail\":";
    const size_t tailLimit = std::min<size_t>(samples.size(), 10);
    json << "[";
    for (size_t index = samples.size() - tailLimit; index < samples.size(); ++index) {
        if (tailLimit == 0) {
            break;
        }
        if (index > samples.size() - tailLimit) {
            json << ",";
        }
        AppendEchoSampleObject(json, samples[index]);
    }
    json << "],"
          << "\"timing_samples_recorded\":" << samples.size() << ","
         << "\"timing_samples_embedded\":" << sampleLimit << ","
         << "\"library_info\":\"" << JsonEscape(SafeString(lsl_library_info())) << "\","
         << "\"last_pull_error_code\":" << lastPullError << ","
         << "\"last_push_error_code\":" << lastPushError << ","
         << "\"issue_codes\":[";
    bool wroteIssue = false;
    if (commandSamplesReceived != sampleCount || echoSamplesPublished != sampleCount) {
        json << "\"rusty.quest.issue.qcl081_lsl_echo_incomplete\"";
        wroteIssue = true;
    }
    if (!monotonicSequences) {
        if (wroteIssue) {
            json << ",";
        }
        json << "\"rusty.quest.issue.qcl081_lsl_echo_sequence_non_monotonic\"";
        wroteIssue = true;
    }
    if (sequenceGapCount > 0) {
        if (wroteIssue) {
            json << ",";
        }
        json << "\"rusty.quest.issue.qcl081_lsl_command_sequence_gap\"";
        wroteIssue = true;
    }
    if (deadlineExpired) {
        if (wroteIssue) {
            json << ",";
        }
        json << "\"rusty.quest.issue.qcl081_lsl_command_receive_deadline_expired\"";
        wroteIssue = true;
    }
    if (pullFailed) {
        if (wroteIssue) {
            json << ",";
        }
        json << "\"rusty.quest.issue.qcl081_lsl_command_pull_failed\"";
        wroteIssue = true;
    }
    if (pushFailed) {
        if (wroteIssue) {
            json << ",";
        }
        json << "\"rusty.quest.issue.qcl081_lsl_echo_push_failed\"";
    }
    json << "],"
         << "\"notes\":\"Quest liblsl inlet received Windows command samples and immediately echoed Quest receive/send timestamps on a Quest-owned outlet.\""
         << "}";

    return NewJsonString(env, json.str());
}
