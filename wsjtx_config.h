#ifndef WSJTX_CONFIG_H__
#define WSJTX_CONFIG_H__

#ifdef __cplusplus
extern "C"  {
#endif

#define WSJTX_VERSION_MAJOR 2
#define WSJTX_VERSION_MINOR 5
#define WSJTX_VERSION_PATCH 0-NOT_FOR_RELEASE

#define CMAKE_INSTALL_DATAROOTDIR "share"
#define CMAKE_INSTALL_DOCDIR "share/doc/JS8Call-improved"
#define CMAKE_INSTALL_DATADIR "share"
#define CMAKE_PROJECT_NAME "JS8Call-improved"
#define PROJECT_HOMEPAGE "https://groups.io/g/js8call"
#define PROJECT_SUMMARY_DESCRIPTION "JS8Call-improved - Digital Modes for Weak Signal Communications in Amateur Radio."

#define WSJT_SHARED_RUNTIME 0
#define WSJT_QDEBUG_TO_FILE 0
#define WSJT_QDEBUG_IN_RELEASE 0
#define WSJT_HAMLIB_TRACE 0
#define WSJT_HAMLIB_VERBOSE_TRACE 0
#define WSJT_ENABLE_EXPERIMENTAL_FEATURES 0
#define WSJT_RIG_NONE_CAN_SPLIT 0

#define WSJTX_STRINGIZE1(x) #x
#define WSJTX_STRINGIZE(x) WSJTX_STRINGIZE1(x)

/* consistent UNICODE behaviour */
#ifndef UNICODE
#	undef _UNICODE
#else
#	ifndef _UNICODE
#		define _UNICODE
#	endif
#endif

#ifdef __cplusplus
}
#endif

#endif
