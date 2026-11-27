//! Hand-written EGL / GLES 3.0 / ANativeWindow / log declarations (Zig 0.17 has no
//! @cImport and NDK headers do not translate). Values copied from NDK 30
//! sysroot headers: EGL/egl.h, GLES3/gl3.h, android/native_window.h, android/log.h.
//! Only referenced from Android-gated code, so host builds never link these.

pub const EGLDisplay = ?*anyopaque;
pub const EGLConfig = ?*anyopaque;
pub const EGLSurface = ?*anyopaque;
pub const EGLContext = ?*anyopaque;
pub const EGLint = i32;
pub const EGLBoolean = c_uint;

pub const EGL_NO_DISPLAY: EGLDisplay = null;
pub const EGL_NO_CONTEXT: EGLContext = null;
pub const EGL_NO_SURFACE: EGLSurface = null;
pub const EGL_DEFAULT_DISPLAY: ?*anyopaque = null;
pub const EGL_ALPHA_SIZE: EGLint = 0x3021;
pub const EGL_BLUE_SIZE: EGLint = 0x3022;
pub const EGL_GREEN_SIZE: EGLint = 0x3023;
pub const EGL_RED_SIZE: EGLint = 0x3024;
pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
pub const EGL_NONE: EGLint = 0x3038;
pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
pub const EGL_HEIGHT: EGLint = 0x3056;
pub const EGL_WIDTH: EGLint = 0x3057;
pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
pub const EGL_WINDOW_BIT: EGLint = 0x0004;
pub const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;

pub extern "EGL" fn eglGetDisplay(display_id: ?*anyopaque) EGLDisplay;
pub extern "EGL" fn eglInitialize(dpy: EGLDisplay, major: ?*EGLint, minor: ?*EGLint) EGLBoolean;
pub extern "EGL" fn eglChooseConfig(dpy: EGLDisplay, attrib_list: [*]const EGLint, configs: [*]EGLConfig, config_size: EGLint, num_config: *EGLint) EGLBoolean;
pub extern "EGL" fn eglCreateWindowSurface(dpy: EGLDisplay, config: EGLConfig, win: ?*anyopaque, attrib_list: ?[*]const EGLint) EGLSurface;
pub extern "EGL" fn eglCreateContext(dpy: EGLDisplay, config: EGLConfig, share: EGLContext, attrib_list: [*]const EGLint) EGLContext;
pub extern "EGL" fn eglMakeCurrent(dpy: EGLDisplay, draw: EGLSurface, read: EGLSurface, ctx: EGLContext) EGLBoolean;
pub extern "EGL" fn eglSwapInterval(dpy: EGLDisplay, interval: EGLint) EGLBoolean;
pub extern "EGL" fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) EGLBoolean;
pub extern "EGL" fn eglQuerySurface(dpy: EGLDisplay, surface: EGLSurface, attribute: EGLint, value: *EGLint) EGLBoolean;
pub extern "EGL" fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) EGLBoolean;
pub extern "EGL" fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) EGLBoolean;
pub extern "EGL" fn eglReleaseThread() EGLBoolean;

pub const GLenum = c_uint;
pub const GLuint = c_uint;
pub const GLint = c_int;
pub const GLsizei = c_int;
pub const GLfloat = f32;
pub const GLboolean = u8;
pub const GLbitfield = c_uint;
pub const GLchar = u8;
pub const GLintptr = isize;
pub const GLsizeiptr = isize;

pub const GL_TRIANGLE_STRIP: GLenum = 0x0005;
pub const GL_ONE: GLenum = 1;
pub const GL_SRC_ALPHA: GLenum = 0x0302;
pub const GL_ONE_MINUS_SRC_ALPHA: GLenum = 0x0303;
pub const GL_BLEND: GLenum = 0x0BE2;
pub const GL_SCISSOR_TEST: GLenum = 0x0C11;
pub const GL_UNPACK_ALIGNMENT: GLenum = 0x0CF5;
pub const GL_TEXTURE_2D: GLenum = 0x0DE1;
pub const GL_UNSIGNED_BYTE: GLenum = 0x1401;
pub const GL_FLOAT: GLenum = 0x1406;
pub const GL_RED: GLenum = 0x1903;
pub const GL_RGBA: GLenum = 0x1908;
pub const GL_NEAREST: GLint = 0x2600;
pub const GL_LINEAR: GLint = 0x2601;
pub const GL_TEXTURE_MAG_FILTER: GLenum = 0x2800;
pub const GL_TEXTURE_MIN_FILTER: GLenum = 0x2801;
pub const GL_TEXTURE_WRAP_S: GLenum = 0x2802;
pub const GL_TEXTURE_WRAP_T: GLenum = 0x2803;
pub const GL_COLOR_BUFFER_BIT: GLbitfield = 0x4000;
pub const GL_RGBA8: GLint = 0x8058;
pub const GL_CLAMP_TO_EDGE: GLint = 0x812F;
pub const GL_R8: GLint = 0x8229;
pub const GL_TEXTURE0: GLenum = 0x84C0;
pub const GL_ARRAY_BUFFER: GLenum = 0x8892;
pub const GL_DYNAMIC_DRAW: GLenum = 0x88E8;
pub const GL_FRAGMENT_SHADER: GLenum = 0x8B30;
pub const GL_VERTEX_SHADER: GLenum = 0x8B31;
pub const GL_COMPILE_STATUS: GLenum = 0x8B81;
pub const GL_LINK_STATUS: GLenum = 0x8B82;
pub const GL_TEXTURE_2D_ARRAY: GLenum = 0x8C1A;

pub extern "GLESv3" fn glViewport(x: GLint, y: GLint, w: GLsizei, h: GLsizei) void;
pub extern "GLESv3" fn glClearColor(r: GLfloat, g: GLfloat, b: GLfloat, a: GLfloat) void;
pub extern "GLESv3" fn glClear(mask: GLbitfield) void;
pub extern "GLESv3" fn glEnable(cap: GLenum) void;
pub extern "GLESv3" fn glDisable(cap: GLenum) void;
pub extern "GLESv3" fn glBlendFunc(s: GLenum, d: GLenum) void;
pub extern "GLESv3" fn glPixelStorei(pname: GLenum, param: GLint) void;
pub extern "GLESv3" fn glCreateShader(kind: GLenum) GLuint;
pub extern "GLESv3" fn glShaderSource(shader: GLuint, count: GLsizei, string: [*]const [*]const GLchar, length: ?[*]const GLint) void;
pub extern "GLESv3" fn glCompileShader(shader: GLuint) void;
pub extern "GLESv3" fn glGetShaderiv(shader: GLuint, pname: GLenum, params: *GLint) void;
pub extern "GLESv3" fn glGetShaderInfoLog(shader: GLuint, max: GLsizei, len: ?*GLsizei, log: [*]GLchar) void;
pub extern "GLESv3" fn glDeleteShader(shader: GLuint) void;
pub extern "GLESv3" fn glCreateProgram() GLuint;
pub extern "GLESv3" fn glAttachShader(program: GLuint, shader: GLuint) void;
pub extern "GLESv3" fn glLinkProgram(program: GLuint) void;
pub extern "GLESv3" fn glGetProgramiv(program: GLuint, pname: GLenum, params: *GLint) void;
pub extern "GLESv3" fn glUseProgram(program: GLuint) void;
pub extern "GLESv3" fn glDeleteProgram(program: GLuint) void;
pub extern "GLESv3" fn glGetUniformLocation(program: GLuint, name: [*:0]const GLchar) GLint;
pub extern "GLESv3" fn glUniform1i(loc: GLint, v: GLint) void;
pub extern "GLESv3" fn glUniform2f(loc: GLint, a: GLfloat, b: GLfloat) void;
pub extern "GLESv3" fn glGenVertexArrays(n: GLsizei, arrays: [*]GLuint) void;
pub extern "GLESv3" fn glBindVertexArray(array: GLuint) void;
pub extern "GLESv3" fn glDeleteVertexArrays(n: GLsizei, arrays: [*]const GLuint) void;
pub extern "GLESv3" fn glGenBuffers(n: GLsizei, buffers: [*]GLuint) void;
pub extern "GLESv3" fn glBindBuffer(target: GLenum, buffer: GLuint) void;
pub extern "GLESv3" fn glBufferData(target: GLenum, size: GLsizeiptr, data: ?*const anyopaque, usage: GLenum) void;
pub extern "GLESv3" fn glBufferSubData(target: GLenum, offset: GLintptr, size: GLsizeiptr, data: ?*const anyopaque) void;
pub extern "GLESv3" fn glDeleteBuffers(n: GLsizei, buffers: [*]const GLuint) void;
pub extern "GLESv3" fn glEnableVertexAttribArray(index: GLuint) void;
pub extern "GLESv3" fn glVertexAttribPointer(index: GLuint, size: GLint, kind: GLenum, normalized: GLboolean, stride: GLsizei, pointer: ?*const anyopaque) void;
pub extern "GLESv3" fn glVertexAttribDivisor(index: GLuint, divisor: GLuint) void;
pub extern "GLESv3" fn glDrawArraysInstanced(mode: GLenum, first: GLint, count: GLsizei, instances: GLsizei) void;
pub extern "GLESv3" fn glGenTextures(n: GLsizei, textures: [*]GLuint) void;
pub extern "GLESv3" fn glDeleteTextures(n: GLsizei, textures: [*]const GLuint) void;
pub extern "GLESv3" fn glBindTexture(target: GLenum, texture: GLuint) void;
pub extern "GLESv3" fn glActiveTexture(texture: GLenum) void;
pub extern "GLESv3" fn glTexParameteri(target: GLenum, pname: GLenum, param: GLint) void;
pub extern "GLESv3" fn glTexImage2D(target: GLenum, level: GLint, internal: GLint, w: GLsizei, h: GLsizei, border: GLint, format: GLenum, kind: GLenum, pixels: ?*const anyopaque) void;
pub extern "GLESv3" fn glTexImage3D(target: GLenum, level: GLint, internal: GLint, w: GLsizei, h: GLsizei, d: GLsizei, border: GLint, format: GLenum, kind: GLenum, pixels: ?*const anyopaque) void;
pub extern "GLESv3" fn glTexSubImage3D(target: GLenum, level: GLint, x: GLint, y: GLint, z: GLint, w: GLsizei, h: GLsizei, d: GLsizei, format: GLenum, kind: GLenum, pixels: ?*const anyopaque) void;

pub extern "android" fn ANativeWindow_release(window: *anyopaque) void;
pub extern "android" fn ANativeWindow_getWidth(window: *anyopaque) i32;
pub extern "android" fn ANativeWindow_getHeight(window: *anyopaque) i32;

// The window's scaling mode is not in the NDK: `native_window_set_scaling_mode` is an inline
// function of the platform header that calls the window's `perform` hook. Values and layout
// from AOSP frameworks/native, android16-release:
//   libs/nativewindow/include/system/window.h: NATIVE_WINDOW_SET_SCALING_MODE = 10,
//     NATIVE_WINDOW_SCALING_MODE_FREEZE = 0, NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW = 1,
//     ANDROID_NATIVE_WINDOW_MAGIC = ANDROID_NATIVE_MAKE_CONSTANT('_','w','n','d'),
//     `struct ANativeWindow` (common.version is its sizeof);
//   libs/nativebase/include/nativebase/nativebase.h: `struct android_native_base_t`.
// EGL drivers call the same hooks through this layout, so it does not change between releases.
pub const NATIVE_WINDOW_SET_SCALING_MODE: c_int = 10;
pub const NATIVE_WINDOW_SCALING_MODE_FREEZE: c_int = 0;
pub const NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW: c_int = 1;
pub const ANDROID_NATIVE_WINDOW_MAGIC: c_int = ('_' << 24) | ('w' << 16) | ('n' << 8) | 'd';

pub const NativeBase = extern struct {
    magic: c_int,
    version: c_int,
    reserved: [4]?*anyopaque,
    incRef: ?*const anyopaque,
    decRef: ?*const anyopaque,
};

/// `struct ANativeWindow` up to `perform`; the hooks after it are not used here.
pub const NativeWindow = extern struct {
    common: NativeBase,
    flags: u32,
    minSwapInterval: c_int,
    maxSwapInterval: c_int,
    xdpi: f32,
    ydpi: f32,
    oem: [4]isize,
    setSwapInterval: ?*const anyopaque,
    dequeueBuffer_DEPRECATED: ?*const anyopaque,
    lockBuffer_DEPRECATED: ?*const anyopaque,
    queueBuffer_DEPRECATED: ?*const anyopaque,
    query: ?*const anyopaque,
    perform: ?*const fn (window: *NativeWindow, operation: c_int, ...) callconv(.c) c_int,
};

pub const ANDROID_LOG_INFO: c_int = 4;
pub const ANDROID_LOG_WARN: c_int = 5;
pub extern "log" fn __android_log_write(prio: c_int, tag: [*:0]const u8, text: [*:0]const u8) c_int;
