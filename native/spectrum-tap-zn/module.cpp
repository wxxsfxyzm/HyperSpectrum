// SPDX-License-Identifier: Apache-2.0
#include "engine.h"
#include "elf_image.h"
#include "track_layout.h"
#include "lazy_engine.h"
#include "io.h"
#include "zn_api.h"
#include <dlfcn.h>
#include <elf.h>
#include <link.h>
#include <pthread.h>
#include <sys/random.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <new>
#include <atomic>
#include <algorithm>

namespace {
using namespace tap;
struct Buffer { const void* raw; size_t frames; };
static_assert(sizeof(Buffer) == 16 && offsetof(Buffer, frames) == 8);
using Intercept = void (*)(void*, const Buffer*);
Intercept original = nullptr;
// The library is also loaded into a root companion. Never construct the PCM
// rings/FFT tables in ELF constructors, the loader callback or that companion.
LazyEngine active_engine;
ZygiskNextAPI zn{};
void* module_handle = nullptr;
Health health = Health::Starting;
uint64_t instance = 0;
TrackLayout track_layout;

template <typename T> T field(const void* object, size_t offset) {
    T value;
    std::memcpy(&value, static_cast<const unsigned char*>(object) + offset, sizeof(T));
    return value;
}
Metadata metadata(void* track) {
    Metadata m{};
    m.app_gain_known = false;
    m.uid = field<uint32_t>(track, track_layout.uid); m.session = field<uint32_t>(track, track_layout.session);
    m.id = field<uint32_t>(track, track_layout.id); m.io = field<uint32_t>(track, track_layout.io);
    m.state = __atomic_load_n(reinterpret_cast<const uint32_t*>(
        static_cast<const unsigned char*>(track) + track_layout.state), __ATOMIC_RELAXED);
    m.usage = field<uint32_t>(track, track_layout.usage); m.stream = field<uint32_t>(track, track_layout.stream);
    m.type = field<uint32_t>(track, track_layout.type); m.format = field<uint32_t>(track, track_layout.format);
    m.mask = field<uint32_t>(track, track_layout.mask);
    m.channels = uint32_t(__builtin_popcount(m.mask));
    m.frame_size = field<uint64_t>(track, track_layout.frame_size);
    const uint32_t flags = field<uint32_t>(track, track_layout.flags);
    // No compressed/direct/MMAP observation, including PCM direct paths.
    if (flags & 0x4011u) m.type = UINT32_MAX;
    const auto* proxy = field<const unsigned char*>(track, track_layout.proxy);
    if (proxy) {
        const auto* cblk = field<const unsigned char*>(proxy, track_layout.cblk);
        if (cblk) {
            m.rate = __atomic_load_n(reinterpret_cast<const uint32_t*>(cblk + track_layout.rate), __ATOMIC_RELAXED);
            m.app_gain = __atomic_load_n(reinterpret_cast<const uint32_t*>(cblk + track_layout.gain), __ATOMIC_RELAXED);
            m.app_gain_known = true;
            m.app_muted = m.app_gain == 0;
        }
    }
    return m;
}
void intercepted(void* track, const Buffer* buffer) {
    if (!track || !buffer || field<uintptr_t>(track, 0) != track_layout.vtable) {
        original(track, buffer); return;
    }
    Engine* current = active_engine.get();
    if (!current) { original(track, buffer); return; }
    Engine& engine = *current;
    const auto expires = engine.deadline.load(std::memory_order_acquire);
    const auto start = expires ? monotonic_ns() : 0;
    if (expires && start < expires) {
        const auto m = metadata(track);
        engine.observe(m, buffer->raw, buffer->frames, uint32_t(syscall(SYS_gettid)), start);
    }
    original(track, buffer);
}

struct Image : ElfImage { const char* path = kLibrary; unsigned matches = 0; };
int find_image(dl_phdr_info* info, size_t, void* arg) {
    auto& image=*static_cast<Image*>(arg);
    const char* name=std::strrchr(info->dlpi_name,'/');name=name?name+1:info->dlpi_name;
    if(std::strcmp(name,"libaudioflinger.so"))return 0;
    if(++image.matches!=1)return 0;
    image.bias=info->dlpi_addr;image.phdr=info->dlpi_phdr;image.count=info->dlpi_phnum;
    image.path=info->dlpi_name;return 0;
}
bool install() {
    Image image;dl_iterate_phdr(find_image,&image);
    if(!image.bias||image.matches!=1)return false;
    ZnSymbolResolver* resolver=zn.newSymbolResolver(image.path,reinterpret_cast<void*>(image.bias));
    if(!resolver)return false;
    auto lookup=[&](const char* name,size_t& size){
        return reinterpret_cast<uintptr_t>(zn.symbolLookup(resolver,name,false,&size));
    };
    size_t target_size=0,table_size=0,rate_size=0,volume_size=0,ctor_size=0;
    const auto target=lookup(kSymbol,target_size);
    const auto table_symbol=lookup("_ZTVN7android5TrackE",table_size);
    const auto rate=lookup("_ZNK7android5Track10sampleRateEv",rate_size);
    const auto volume=lookup("_ZNK7android5Track11getVolumeLREv",volume_size);
    const auto ctor=lookup("_ZN7android9TrackBaseC2EPNS_13IAfThreadBaseERKNS_2spINS_6ClientEEERK18audio_attributes_tj14audio_format_t20audio_channel_mask_tmPvm15audio_session_tijbNS_12IAfTrackBase10alloc_typeENSF_10track_typeEiNSt3__112basic_stringIcNSI_11char_traitsIcEENSI_9allocatorIcEEEERKSO_",ctor_size);
    size_t a=0,b=0,c=0;
    const auto destructor=lookup("_ZN7android5TrackD1Ev",a);
    const auto next=lookup("_ZN7android5Track13getNextBufferEPNS_19AudioBufferProvider6BufferE",b);
    const auto release=lookup("_ZN7android5Track13releaseBufferEPNS_19AudioBufferProvider6BufferE",c);
    zn.freeSymbolResolver(resolver);
    if(!target||!target_size||target_size>65536||!image.contains(target,target_size,PF_R|PF_X)||
       !table_symbol||table_size<104*8||table_size>65536||!image.contains(table_symbol,table_size,PF_R)||
       !rate||!volume||!ctor||!ctor_size||ctor_size>65536||!image.contains(ctor,ctor_size,PF_R|PF_X))return false;
    // Locate the primary address point from named function anchors rather than
    // assuming vtable+0x30. Unknown virtual-interface layouts fail closed.
    uintptr_t table=0;unsigned matches=0;
    for(size_t off=0;off+104*8<=table_size;off+=8){
        uintptr_t d=0,n=0,r=0;
        if(!image.read(table_symbol+off,d)||!image.read(table_symbol+off+16,n)||!image.read(table_symbol+off+24,r))return false;
        if(d==destructor&&n==next&&r==release){table=table_symbol+off;++matches;}
    }
    if(matches!=1)return false;
    auto read=[&](uintptr_t address,auto& value){
        // Instruction decoders inspect only executable code in this image.
        if constexpr (sizeof(value)==sizeof(uint32_t)) {
            if(!image.contains(address,sizeof(value),PF_R|PF_X))return false;
        }
        return image.read(address,value);
    };
    if(!discover_track_layout(read,table,rate,volume,ctor,ctor_size,track_layout))return false;
    uintptr_t got=0,previous_binding=0;
    if(!image.jump_slot(kSymbol,got)||!image.read(got,previous_binding)||previous_binding!=target)return false;
    // Preserve original text, PAC and BTI; only patch the symbol's PLT import.
    uint32_t entry[4]{};if(!image.read(target,entry))return false;
    original=reinterpret_cast<Intercept>(target);health=Health::HookFailed;
    void* previous=nullptr;
    if(zn.pltHook(reinterpret_cast<void*>(image.bias),kSymbol,reinterpret_cast<void*>(intercepted),&previous))return false;
    uintptr_t binding=0;uint32_t after[4]{};
    if(previous==reinterpret_cast<void*>(target)&&image.read(got,binding)&&binding==reinterpret_cast<uintptr_t>(intercepted)&&
       image.read(target,after)&&!std::memcmp(entry,after,sizeof(entry)))return true;
    zn.pltHook(reinterpret_cast<void*>(image.bias),kSymbol,reinterpret_cast<void*>(target),nullptr);
    return false;
}

Engine* allocate_engine() {
    // Called only for a validated capture request, never on library load or
    // idle companion connection. Failure leaves the hook as a pass-through.
    void* storage = mmap(nullptr, sizeof(Engine), PROT_READ | PROT_WRITE,
                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (storage == MAP_FAILED) return nullptr;
    return new (storage) Engine;
}

void* worker(void*) {
    Engine* engine = nullptr;
    Report report{};
    report.pid = uint32_t(getpid()); report.instance = instance;
    std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
    report.health = uint32_t(health);
    uint64_t next_report = 0, last_command = 0, sent_spectrum = 0;
    bool signal_pending = false;
    for (;;) {
        int fd = zn.connectCompanion(module_handle);
        if (fd < 0) { if (engine) engine->stop(); usleep(1000000); continue; }
        bool connected = true;
        while (connected) {
            const auto now = monotonic_ns();
            // A short lease also fails closed if the worker/companion stalls.
            const auto expiry = engine ? engine->deadline.load(std::memory_order_acquire) : 0;
            if (expiry && now >= expiry) engine->stop();
            if (engine && engine->deadline.load()) {
                engine->drain(report);
                // Forward each new FFT frame at once; between frames only a heartbeat.
                if (report.spectrum.sequence != sent_spectrum) next_report = 0;
            } else if (engine && signal_pending && engine->clear_signal(&report)) {
                signal_pending = false; report.spectrum = SpectrumFrame{};
            }
            if (now >= next_report) {
                report.reserved &= 241u;
                report.now_ns = now; ++report.report_sequence;
                if (engine) engine->counters(report);
                report.enabled = now < report.deadline_ns;
                connected = send_value(fd, report);
                sent_spectrum = report.spectrum.sequence;
                next_report = now + (report.enabled ? 250000000 : 500000000);
            }
            if (!connected) break;
            pollfd p{fd, POLLIN, 0};
            // A lease drains the 4-block rings every 10 ms (blocks seen on device are
            // >= ~9.7 ms each); idle sleeps until the next heartbeat or a command.
            const int64_t until_report = (int64_t(next_report) - int64_t(monotonic_ns())) / 1000000;
            const int timeout = expiry && now < expiry ? 10 : int(std::clamp<int64_t>(until_report, 1, 500));
            const int ready = poll(&p, 1, timeout);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0 || (p.revents & (POLLHUP | POLLERR | POLLNVAL))) break;
            if (ready > 0 && (p.revents & POLLIN)) {
                Command cmd;
                if (!receive(fd, cmd)) break;
                if (!valid(cmd, instance, last_command, monotonic_ns())) { if (engine) engine->stop(); continue; }
                last_command = cmd.sequence;
                if (cmd.seconds == 0) {
                    if (engine) engine->stop();
                    report.command_sequence = last_command;
                    next_report = 0; continue;
                }
                if (cmd.reserved == 1 && report.client_generation == cmd.client_generation &&
                    engine && engine->renew(cmd, monotonic_ns())) {
                    report.command_sequence = last_command; continue;
                }
                if (health != Health::Ready) continue;
                if (!engine) engine = active_engine.ensure(allocate_engine);
                // FFT table construction can outlast a short request. Recheck
                // freshness before granting a lease; never activate stale work.
                if (!engine) continue;
                if (!valid(cmd, instance, 0, monotonic_ns()) ||
                    !engine->configure(cmd, monotonic_ns())) { engine->stop(); continue; }
                const auto seq = report.report_sequence;
                report = Report{}; report.pid = uint32_t(getpid()); report.instance = instance;
                std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
                report.health = uint32_t(health); report.report_sequence = seq;
                report.command_sequence = last_command;
                report.client_generation = cmd.client_generation;
                signal_pending = true;
                report.uid = cmd.uid; report.session = cmd.session; report.io = cmd.io;
                next_report = 0;
            }
        }
        if (engine) engine->stop();
        // Clear pending PCM on disconnect after producer quiescence. No replay.
        Command off; if (engine) engine->configure(off, monotonic_ns());
        report = Report{};
        report.pid = uint32_t(getpid()); report.health = uint32_t(health);
        std::memcpy(report.source_build, TAP_SOURCE_BUILD, sizeof(report.source_build));
        if (getrandom(&instance, sizeof(instance), GRND_NONBLOCK) != sizeof(instance) || !instance) return nullptr;
        report.instance = instance;
        last_command = 0; next_report = 0;
        close(fd); usleep(1000000);
    }
}

void loaded(void* handle, const ZygiskNextAPI* api) {
    char exe[256]{}; const ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0 || std::strcmp(exe, "/system/bin/audioserver") != 0 || getuid() != 1041 ||
        !exact_file("/proc/self/cmdline", "/system/bin/audioserver") ||
        !exact_file("/proc/self/attr/current", "u:r:audioserver:s0") || !api ||
        !api->pltHook || !api->newSymbolResolver || !api->symbolLookup ||
        !api->freeSymbolResolver || !api->connectCompanion) return;
    zn = *api; module_handle = handle;
    if (getrandom(&instance, sizeof(instance), GRND_NONBLOCK) != sizeof(instance) || !instance) return;
    health = Health::BinaryRejected;
    if (install()) health = Health::Ready;
    pthread_t thread;
    if (pthread_create(&thread, nullptr, worker, nullptr) == 0) pthread_detach(thread);
    // No dlclose/unhook from a live audio process. Upgrade requires a fresh process.
}
}  // namespace
extern "C" {
__attribute__((visibility("default"), used)) ZygiskNextModule zn_module = {4, loaded};
}
