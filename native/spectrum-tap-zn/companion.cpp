// SPDX-License-Identifier: Apache-2.0
#include "io.h"
#include "protocol.h"
#include "app_gain.h"
#include "zn_api.h"
#include "ui_bridge.h"
#include <atomic>
#include <algorithm>
#include <cstdio>
#include <pthread.h>
#include <sys/stat.h>

namespace {
using namespace tap;
std::atomic<bool> busy{false};
constexpr const char* command_path = "/data/adb/hyperspectrum-tap/command";
bool directory_ok() {
    struct stat st{};
    return lstat(kDirectory, &st) == 0 && S_ISDIR(st.st_mode) && st.st_uid == 0 &&
           (st.st_mode & 0777) == 0700;
}
bool producer_ok(uint32_t pid) {
    if (pid <= 1 || pid > 4194304) return false;
    char path[128], exe[256]{};
    std::snprintf(path, sizeof(path), "/proc/%u/exe", pid);
    if (readlink(path, exe, sizeof(exe) - 1) <= 0 || std::strcmp(exe, "/system/bin/audioserver")) return false;
    std::snprintf(path, sizeof(path), "/proc/%u/attr/current", pid);
    if (!exact_file(path, "u:r:audioserver:s0")) return false;
    std::snprintf(path, sizeof(path), "/proc/%u", pid);
    struct stat st{};
    return stat(path, &st) == 0 && st.st_uid == 1041;
}
bool peer_ok(int fd) {
    ucred cred{}; socklen_t size = sizeof(cred);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &size) || size != sizeof(cred)) return false;
    // ZN may create the socketpair in its root daemon before handing the fd
    // to the target. This is a ZN-owned channel, never a public socket listener.
    if (cred.uid != 0 && cred.uid != 1041) return false;
    char context[128]{}; size = sizeof(context);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERSEC, context, &size) || size >= sizeof(context)) return false;
    return std::strcmp(context, "u:r:ksu:s0") == 0 || std::strcmp(context, "u:r:audioserver:s0") == 0;
}
bool read_command(Command& c) {
    const int fd = open(command_path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK);
    if (fd < 0) return false;
    struct stat st{};
    const bool metadata = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 &&
                          st.st_nlink == 1 && (st.st_mode & 0777) == 0600 && st.st_size == sizeof(c);
    const ssize_t size = metadata ? read(fd, &c, sizeof(c)) : -1;
    close(fd);
    // One-shot request consumed even if malformed. Never replay on reconnect.
    unlink(command_path);
    return metadata && size == sizeof(c);
}
void status(const Report& r, bool connected, const UiBridge* ui = nullptr) {
    if (!directory_ok()) return;
    const char* tmp = "/data/adb/hyperspectrum-tap/status.tmp";
    const int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return;
    FILE* f = fdopen(fd, "w");
    if (!f) { close(fd); return; }
    std::fprintf(f, "{\"abi\":2,\"build\":\"%.64s\",\"connected\":%s,\"producer_pid\":%u,\"instance\":\"%016llx\","
        "\"sequence\":%llu,\"now_ns\":%llu,\"command_sequence\":%llu,\"health\":%u,\"enabled\":%s,"
        "\"uid\":%u,\"session\":%u,\"io\":%u,\"deadline_ns\":%llu,\"entered\":%llu,\"eligible\":%llu,"
        "\"rejected\":%llu,\"app_muted\":%llu,\"oversize\":%llu,\"slots_full\":%llu,"
        "\"copy_ns_sum\":%llu,\"copy_ns_max\":%llu,\"tracks\":[",
        r.source_build, connected ? "true" : "false", r.pid, (unsigned long long)r.instance,
        (unsigned long long)r.report_sequence, (unsigned long long)r.now_ns,
        (unsigned long long)r.command_sequence, r.health, r.enabled ? "true" : "false",
        r.uid, r.session, r.io, (unsigned long long)r.deadline_ns,
        (unsigned long long)r.entered, (unsigned long long)r.eligible,
        (unsigned long long)r.rejected, (unsigned long long)r.app_muted,
        (unsigned long long)r.oversize, (unsigned long long)r.slots_full,
        (unsigned long long)r.copy_ns_sum, (unsigned long long)r.copy_ns_max);
    for (uint32_t i = 0; i < kSlots; ++i) {
        const auto& t = r.tracks[i];
        std::fprintf(f, "%s{\"id\":%u,\"tid\":%u,\"format\":%u,\"rate\":%u,\"channels\":%u,\"io\":%u,"
            "\"blocks\":%llu,\"frames\":%llu,\"samples\":%llu,\"nonzero\":%llu,\"changed\":%llu,"
            "\"drops\":%llu,\"contention\":%llu,\"discontinuities\":%llu,\"invalid_samples\":%llu,"
            "\"last_ns\":%llu,\"max_gap_ns\":%llu,\"rms_min\":%.12g,\"rms_max\":%.12g,"
            "\"rms_last\":%.12g,\"peak\":%.12g}", i ? "," : "", t.id, t.owner_tid, t.format,
            t.rate, t.channels, t.io, (unsigned long long)t.blocks, (unsigned long long)t.frames,
            (unsigned long long)t.samples, (unsigned long long)t.nonzero, (unsigned long long)t.changed,
            (unsigned long long)t.drops, (unsigned long long)t.contention,
            (unsigned long long)t.discontinuities, (unsigned long long)t.invalid_samples,
            (unsigned long long)t.last_ns, (unsigned long long)t.max_gap_ns,
            t.rms_min, t.rms_max, t.rms_last, t.peak);
    }
    std::fprintf(f, "],\"client_generation\":%llu,\"fft_frames\":%llu,\"fft_sequence\":%llu,\"fft_ns\":%llu,\"ui_connected\":%s,\"ui_errno\":%d,\"capture_stage\":\"%s\",\"converted_hook_ready\":%s,\"converted_provider_seen\":%s,\"converted_commit_seen\":%s,\"app_gain_applied\":%s,\"app_gain_fallback\":%s,\"app_gain_packed\":%u,\"app_gain_left\":%.9g,\"app_gain_right\":%.9g,\"analysis_rate\":%u,\"analysis_channels\":%u}\n",
                 (unsigned long long)r.client_generation, (unsigned long long)r.fft_frames,
                 (unsigned long long)r.spectrum.sequence, (unsigned long long)r.spectrum.ns,
                 ui && ui->fd() >= 0 ? "true" : "false", ui ? ui->error() : 0,
                 (r.reserved & 16) ? "spatial_reference_before_gain" : (r.reserved & 128) ? "mono48_source" : (r.reserved & 1) ? "converted_before_gain" : "raw_source",
                 (r.reserved & 2) ? "true" : "false", (r.reserved & 4) ? "true" : "false",
                 (r.reserved & 8) ? "true" : "false", (r.reserved & 32) ? "true" : "false",
                 (r.reserved & 64) ? "true" : "false", r.spectrum.app_gain,
                 app_gain_left(r.spectrum.app_gain), app_gain_right(r.spectrum.app_gain), r.spectrum.rate, r.spectrum.channels);
    const bool good = !ferror(f);
    const int result = fclose(f);
    if (good && result == 0) rename(tmp, "/data/adb/hyperspectrum-tap/status");
    else unlink(tmp);
}
// Fields whose change must reach the status file at once (control.py acknowledgement).
struct StatusKey {
    uint64_t instance = 0, command_sequence = 0, client_generation = 0;
    uint32_t pid = 0, health = 0, enabled = 0, uid = 0, session = 0, io = 0;
    bool ui = false;
    bool operator==(const StatusKey& o) const {
        return instance == o.instance && command_sequence == o.command_sequence &&
               client_generation == o.client_generation && pid == o.pid && health == o.health &&
               enabled == o.enabled && uid == o.uid && session == o.session && io == o.io && ui == o.ui;
    }
};
StatusKey status_key(const Report& r, const UiBridge& ui) {
    return {r.instance, r.command_sequence, r.client_generation, r.pid, r.health, r.enabled,
            r.uid, r.session, r.io, ui.fd() >= 0};
}
void* connected_thread(void* arg) {
    const int fd = int(reinterpret_cast<intptr_t>(arg));
    Report report{};
    bool authenticated = false;
    uint64_t last_sequence = 0, last_command = 0, last_report = monotonic_ns(), next_status = 0;
    uint64_t next_command_check = 0;
    StatusKey written{};
    UiBridge ui;
    auto stop_ui = [&] {
        ui.disconnect();
        if (authenticated) {
            Command off; off.instance = report.instance;
            off.sequence = std::max(last_command, report.command_sequence) + 1;
            last_command = off.sequence;
            return send_value(fd, off);
        }
        return true;
    };
    while (true) {
        // Event driven: producer reports and UI requests wake this thread. The
        // timeout only services the 2 s watchdogs and the debug command file.
        pollfd p[2]{{fd, POLLIN, 0}, {ui.fd(), POLLIN, 0}};
        const int ready = poll(p, 2, 1000);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0 || (p[0].revents & (POLLHUP | POLLERR | POLLNVAL))) break;
        if (p[0].revents & POLLIN) {
            Report next;
            if (!receive(fd, next) || next.magic != kMagic || next.version != kVersion ||
                next.size != sizeof(Report) || !next.instance || next.report_sequence <= last_sequence ||
                (authenticated && (next.instance != report.instance || next.pid != report.pid)) ||
                (!authenticated && !producer_ok(next.pid))) break;
            authenticated = true; report = next; last_sequence = report.report_sequence;
            last_report = monotonic_ns();
            ui.connect_if_needed(report, last_report);
            if (!ui.publish(report) && !stop_ui()) break;
            // Rewrite on a material change, otherwise as a slow heartbeat.
            const auto key = status_key(report, ui);
            if (!(key == written) || last_report >= next_status) {
                status(report, true, &ui); written = key;
                next_status = last_report + (report.enabled ? 2000000000ull : 5000000000ull);
            }
        }
        const auto now = monotonic_ns();
        if (now - last_report > 2000000000) break;
        if (ui.fd() >= 0 && p[1].fd == ui.fd()) {
            if ((p[1].revents & (POLLHUP | POLLERR | POLLNVAL)) || ui.expired(now)) {
                if (!stop_ui()) break;
            } else if (p[1].revents & POLLIN) {
                Command request;
                const auto seq = std::max(last_command, report.command_sequence) + 1;
                if (!ui.request(report, request, seq)) { if (!stop_ui()) break; }
                else { last_command = seq; if (!send_value(fd, request)) break; }
            }
        } else if (ui.expired(now)) {
            if (!stop_ui()) break;
        }
        if (authenticated && now >= next_command_check) {
            next_command_check = now + 1000000000;
            Command command;
            if (directory_ok() && read_command(command) && valid(command, report.instance, last_command, monotonic_ns()) &&
                (ui.fd() < 0 || command.seconds == 0)) {
                last_command = command.sequence;
                if (!send_value(fd, command)) break;
            }
        }
    }
    if (authenticated) { report.enabled = 0; status(report, false); }
    close(fd); busy.store(false, std::memory_order_release);
    return nullptr;
}
void companion_loaded() {
    if (getuid() != 0) return;
    mkdir(kDirectory, 0700);
    if (directory_ok()) {
        unlink(command_path);
        Report empty; status(empty, false);
    }
}
void module_connected(int fd) {
    if (fd < 0) return;
    bool expected = false;
    if (!directory_ok() || !peer_ok(fd) || !busy.compare_exchange_strong(expected, true)) { close(fd); return; }
    pthread_t thread;
    if (pthread_create(&thread, nullptr, connected_thread, reinterpret_cast<void*>(intptr_t(fd))) == 0) {
        pthread_detach(thread);
    } else { close(fd); busy.store(false); }
}
}  // namespace
extern "C" {
__attribute__((visibility("default"), used)) ZygiskNextCompanionModule zn_companion_module = {
    4, companion_loaded, module_connected};
}
