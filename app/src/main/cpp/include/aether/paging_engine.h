// AetherReader native paging core – public C++ interface (libaether.so)
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace aether {

enum class Format : int32_t { Epub, Txt, Pdf, Mobi, Azw3, Cbz, Cbr, Md };
enum class PixelFormat : int32_t { Alpha8, Rgb565, Rgba8888 };

struct Typography {
  std::string font_path;     // empty => bundled font
  int   font_size_pt;
  float line_height;
  int   paragraph_spacing_px;
  float letter_spacing_em;
  int   margin_l, margin_t, margin_r, margin_b;
  int   boldness;            // 0..10 -> FreeType embolden strength
};

struct Viewport { int width, height, columns, dpi; };

struct Location { int32_t chapter; int64_t char_offset; };
struct PageRef  { int32_t chapter; int32_t page; };

// Read-only random-access source. Backed by fd (SAF) or path; never slurps the file.
class ByteSource {
 public:
  virtual ~ByteSource() = default;
  virtual int64_t size() const = 0;
  virtual size_t  read_at(int64_t offset, void* dst, size_t len) = 0;
};

// Container: lists chapters and opens each as a forward-only text/markup stream.
class ChapterStream {
 public:
  virtual ~ChapterStream() = default;
  virtual size_t read(void* dst, size_t len) = 0;  // streaming, bounded buffer (<=64KB)
};

class Container {
 public:
  virtual ~Container() = default;
  virtual int chapter_count() const = 0;
  virtual std::string chapter_title(int i) const = 0;
  virtual std::unique_ptr<ChapterStream> open_chapter(int i) = 0;
  virtual std::unique_ptr<ByteSource> open_resource(const std::string& href) = 0;  // images, css
};

// Result of laying out ONE chapter. Compact: run offsets, not glyph trees.
struct ChapterLayout {
  int32_t chapter;
  std::vector<int64_t> page_start_offsets;  // char offset where each page begins
  size_t memory_bytes() const;
};

class Paginator {
 public:
  virtual ~Paginator() = default;
  // Single-threaded, arena-allocated; cancellable via token.
  virtual std::unique_ptr<ChapterLayout> layout_chapter(
      Container&, int chapter, const Typography&, const Viewport&,
      const volatile bool* cancel) = 0;
};

// Rasterizes a page directly into a caller-owned pixel buffer (Android locked bitmap).
class Rasterizer {
 public:
  virtual ~Rasterizer() = default;
  virtual bool render_page(Container&, const ChapterLayout&, int page,
                           const Typography&, const Viewport&,
                           uint8_t* pixels, int stride, PixelFormat fmt) = 0;
};

class Engine {
 public:
  virtual ~Engine() = default;
  virtual bool open(std::unique_ptr<ByteSource>, Format) = 0;
  virtual int  chapter_count() const = 0;
  // Keeps at most ONE ChapterLayout resident (+ lightweight offset tables for others).
  virtual bool render(PageRef ref, uint8_t* pixels, int stride, PixelFormat fmt) = 0;
  virtual PageRef  locate(Location) = 0;
  virtual Location location_of(PageRef) = 0;
  virtual int  pages_in_chapter(int chapter) = 0;  // triggers layout if absent
  virtual void relayout(const Typography&, const Viewport&) = 0;
  virtual void cancel() = 0;
};

std::unique_ptr<Engine> create_engine();

}  // namespace aether
