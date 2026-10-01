#include <jni.h>
#include <toxcore/tox.h>
#include <vector>
#include <cstdint>
#include <memory>

// All calls and callbacks belong to the Kotlin engine's single worker thread.
struct Engine { Tox *tox = nullptr; JNIEnv *env = nullptr; jobject listener = nullptr; jmethodID event = nullptr; };
static Engine *engine(jlong h) { return reinterpret_cast<Engine *>(h); }
static std::vector<uint8_t> bytes(JNIEnv *e, jbyteArray a) {
    if (!a) return {};
    std::vector<uint8_t> b(e->GetArrayLength(a));
    if (!b.empty()) e->GetByteArrayRegion(a, 0, b.size(), reinterpret_cast<jbyte *>(b.data()));
    return b;
}
static jbyteArray array(JNIEnv *e, const uint8_t *p, size_t n) {
    auto a=e->NewByteArray(n);
    if (a && n) e->SetByteArrayRegion(a,0,n,reinterpret_cast<const jbyte *>(p));
    return a;
}
static void emit(void *u,int type,uint32_t f,const uint8_t *p,size_t n,uint32_t value=0) {
    auto *h=static_cast<Engine *>(u);
    if (!h || !h->listener || h->env->ExceptionCheck()) return;
    auto a=array(h->env,p,n);
    h->env->CallVoidMethod(h->listener,h->event,type,static_cast<jlong>(f),a,static_cast<jlong>(value));
    h->env->DeleteLocalRef(a);
}
extern "C" JNIEXPORT jlong JNICALL Java_app_pulso_music_ToxNative_create(JNIEnv *e,jobject,jbyteArray saved) {
    auto h=std::make_unique<Engine>();
    Tox_Err_Options_New oe; auto *opts=tox_options_new(&oe); if (!opts) return 0;
    tox_options_set_ipv6_enabled(opts,false);
    tox_options_set_local_discovery_enabled(opts,false);
    auto data=bytes(e,saved);
    if (!data.empty()) { tox_options_set_savedata_type(opts,TOX_SAVEDATA_TYPE_TOX_SAVE); tox_options_set_savedata_data(opts,data.data(),data.size()); }
    Tox_Err_New err; h->tox=tox_new(opts,&err); tox_options_free(opts);
    if (!h->tox || err!=TOX_ERR_NEW_OK) { if(h->tox) tox_kill(h->tox); return 0; }
    tox_callback_self_connection_status(h->tox,[](Tox*,Tox_Connection s,void*u){emit(u,0,0,nullptr,0,s);});
    tox_callback_friend_request(h->tox,[](Tox*,const uint8_t*k,const uint8_t*,size_t,void*u){emit(u,1,0,k,TOX_PUBLIC_KEY_SIZE);});
    tox_callback_friend_message(h->tox,[](Tox*,uint32_t f,Tox_Message_Type,const uint8_t*p,size_t n,void*u){emit(u,2,f,p,n);});
    tox_callback_friend_connection_status(h->tox,[](Tox*,uint32_t f,Tox_Connection s,void*u){emit(u,3,f,nullptr,0,s);});
    tox_callback_friend_name(h->tox,[](Tox*,uint32_t f,const uint8_t*p,size_t n,void*u){emit(u,4,f,p,n);});
    tox_callback_friend_read_receipt(h->tox,[](Tox*,uint32_t f,uint32_t id,void*u){emit(u,5,f,nullptr,0,id);});
    tox_callback_friend_lossless_packet(h->tox,[](Tox*,uint32_t f,const uint8_t*p,size_t n,void*u){if(n<=TOX_MAX_CUSTOM_PACKET_SIZE)emit(u,6,f,p,n);});
    return reinterpret_cast<jlong>(h.release());
}
extern "C" JNIEXPORT void JNICALL Java_app_pulso_music_ToxNative_close(JNIEnv*,jobject,jlong p) {auto*h=engine(p);if(h){tox_kill(h->tox);delete h;}}
extern "C" JNIEXPORT jint JNICALL Java_app_pulso_music_ToxNative_iterate(JNIEnv*e,jobject,jlong p,jobject listener) {
    auto*h=engine(p); if(!h)return 100;
    h->env=e;h->listener=listener;
    auto cls=e->GetObjectClass(listener);h->event=e->GetMethodID(cls,"onEvent","(IJ[BJ)V");e->DeleteLocalRef(cls);
    if(h->event)tox_iterate(h->tox,h);
    h->listener=nullptr;h->env=nullptr;return tox_iteration_interval(h->tox);
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_pulso_music_ToxNative_save(JNIEnv*e,jobject,jlong p) {auto*h=engine(p);if(!h)return nullptr;std::vector<uint8_t>b(tox_get_savedata_size(h->tox));tox_get_savedata(h->tox,b.data());return array(e,b.data(),b.size());}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_pulso_music_ToxNative_address(JNIEnv*e,jobject,jlong p) {auto*h=engine(p);if(!h)return nullptr;uint8_t b[TOX_ADDRESS_SIZE];tox_self_get_address(h->tox,b);return array(e,b,sizeof(b));}
extern "C" JNIEXPORT void JNICALL Java_app_pulso_music_ToxNative_name(JNIEnv*e,jobject,jlong p,jbyteArray a) {auto*h=engine(p);auto b=bytes(e,a);if(h&&b.size()<=TOX_MAX_NAME_LENGTH)tox_self_set_name(h->tox,b.data(),b.size(),nullptr);}
extern "C" JNIEXPORT jlong JNICALL Java_app_pulso_music_ToxNative_add(JNIEnv*e,jobject,jlong p,jbyteArray a,jboolean accept) {
    auto*h=engine(p);auto b=bytes(e,a);if(!h||b.size()!=(accept?TOX_PUBLIC_KEY_SIZE:TOX_ADDRESS_SIZE))return -1;
    Tox_Err_Friend_Add err;uint32_t f;
    if(accept)f=tox_friend_add_norequest(h->tox,b.data(),&err);
    else {const uint8_t msg[]="Hola, conectemos en PULSO"; f=tox_friend_add(h->tox,b.data(),msg,sizeof(msg)-1,&err);}
    return err==TOX_ERR_FRIEND_ADD_OK?static_cast<jlong>(f):-1-static_cast<jlong>(err);
}
extern "C" JNIEXPORT jlong JNICALL Java_app_pulso_music_ToxNative_find(JNIEnv*e,jobject,jlong p,jbyteArray a) {auto*h=engine(p);auto b=bytes(e,a);if(!h||b.size()!=TOX_PUBLIC_KEY_SIZE)return -1;Tox_Err_Friend_By_Public_Key err;auto f=tox_friend_by_public_key(h->tox,b.data(),&err);return err==TOX_ERR_FRIEND_BY_PUBLIC_KEY_OK?static_cast<jlong>(f):-1;}
extern "C" JNIEXPORT jbyteArray JNICALL Java_app_pulso_music_ToxNative_key(JNIEnv*e,jobject,jlong p,jlong f) {auto*h=engine(p);uint8_t b[TOX_PUBLIC_KEY_SIZE];if(!h||!tox_friend_get_public_key(h->tox,f,b,nullptr))return nullptr;return array(e,b,sizeof(b));}
extern "C" JNIEXPORT jboolean JNICALL Java_app_pulso_music_ToxNative_remove(JNIEnv*,jobject,jlong p,jlong f) {auto*h=engine(p);return h&&tox_friend_delete(h->tox,f,nullptr);}
extern "C" JNIEXPORT jlong JNICALL Java_app_pulso_music_ToxNative_send(JNIEnv*e,jobject,jlong p,jlong f,jbyteArray a) {auto*h=engine(p);auto b=bytes(e,a);if(!h||b.empty()||b.size()>TOX_MAX_MESSAGE_LENGTH)return -1;Tox_Err_Friend_Send_Message err;auto id=tox_friend_send_message(h->tox,f,TOX_MESSAGE_TYPE_NORMAL,b.data(),b.size(),&err);return err==TOX_ERR_FRIEND_SEND_MESSAGE_OK?static_cast<jlong>(id):-1;}
extern "C" JNIEXPORT jboolean JNICALL Java_app_pulso_music_ToxNative_packet(JNIEnv*e,jobject,jlong p,jlong f,jbyteArray a) {auto*h=engine(p);auto b=bytes(e,a);return h&&!b.empty()&&b.size()<=TOX_MAX_CUSTOM_PACKET_SIZE&&tox_friend_send_lossless_packet(h->tox,f,b.data(),b.size(),nullptr);}
extern "C" JNIEXPORT void JNICALL Java_app_pulso_music_ToxNative_bootstrap(JNIEnv*e,jobject,jlong p,jstring host,jint port,jbyteArray a,jboolean tcp) {auto*h=engine(p);auto b=bytes(e,a);if(!h||b.size()!=TOX_PUBLIC_KEY_SIZE||port<1||port>65535)return;const char*s=e->GetStringUTFChars(host,nullptr);if(!s)return;if(tcp)tox_add_tcp_relay(h->tox,s,port,b.data(),nullptr);else tox_bootstrap(h->tox,s,port,b.data(),nullptr);e->ReleaseStringUTFChars(host,s);}
