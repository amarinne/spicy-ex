#!/usr/bin/env python3
"""Import the pinned Paper Shaders Android AGSL catalog without third-party packages.

Usage: import_paper_shaders.py UPSTREAM_CHECKOUT APP_SRC_MAIN_RESOURCES/paper-shaders
The upstream checkout must be AndreFrelicot/paper-shaders-android at the revision below.
"""

import datetime
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys

REVISION = "fc2d75d4dad54b63bbb07df16d8a2d9a2cc467f3"
ROOT = Path("paper-shaders-compose/src/main/kotlin/dev/andrefrelicot/papershaders")
DYNAMIC = {
    "u_resolution", "u_time", "u_pixelRatio", "u_imageAspectRatio", "u_imageSize",
    "u_noiseTextureSize",
}
SIZING = {
    "u_fit": 0.0,
    "u_scale": 1.0,
    "u_rotation": 0.0,
    "u_originX": 0.5,
    "u_originY": 0.5,
    "u_offsetX": 0.0,
    "u_offsetY": 0.0,
    "u_worldWidth": 0.0,
    "u_worldHeight": 0.0,
}
FIT = {"ShaderFit.None": 0.0, "ShaderFit.Contain": 1.0, "ShaderFit.Cover": 2.0}
WRAPPER = """
// Spicy EX ambient integration: the upstream entry point is paperMain. This wrapper alone
// unpremultiplies safely, applies the existing Kawarp/Mesh grade, and premultiplies the result.
uniform float ambient_dark;
uniform float ambient_brightness;

float ps_ambientDitherHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

vec4 main(vec2 fragCoord) {
  vec4 paperColor = paperMain(fragCoord);
  float alpha = clamp(paperColor.a, 0.0, 1.0);
  vec3 color = clamp(paperColor.rgb / max(alpha, 1e-6), 0.0, 1.0);
  float cssGray = dot(color, vec3(0.213, 0.715, 0.072));
  vec3 normal = clamp(mix(vec3(cssGray), color, 2.5), 0.0, 1.0) * 0.65;
  vec3 dim = (mix(vec3(cssGray), color, 0.9) * 0.38 - 0.5) * 0.95 + 0.5;
  color = mix(normal, dim, ambient_dark) * ambient_brightness;
  float noise = ps_ambientDitherHash(vec3(floor(fragCoord), 0.0));
  color += (noise - 0.5) * 0.008 * min(1.0, ambient_brightness * 255.0);
  color = clamp(color, 0.0, 1.0);
  return vec4(color * alpha, alpha);
}
"""


def sha(data):
    return hashlib.sha256(data).hexdigest()


def number(expression):
    value = expression.strip().rstrip(",")
    if value in FIT:
        return FIT[value]
    if value in ("true", "false"):
        return 1.0 if value == "true" else 0.0
    if re.fullmatch(r"-?(?:\d+(?:\.\d*)?|\.\d+)f", value):
        value = value[:-1]
    if not re.fullmatch(r"-?(?:\d+(?:\.\d*)?|\.\d+)", value):
        raise ValueError("Not a numeric default: " + expression)
    return float(value)


def number_text(value):
    if value == int(value):
        return str(int(value))
    return format(value, ".9g")


def params_block(text, base):
    match = re.search(r"public data class " + re.escape(base) + r"Params\(\n(.*?)^\)", text, re.M | re.S)
    if not match:
        raise ValueError("Missing params for " + base)
    result = {}
    current = None
    for line in match.group(1).splitlines():
        start = re.match(r"    val (\w+): [^=]+ = (.*)", line)
        if start:
            current = start.group(1)
            result[current] = start.group(2)
        elif current:
            result[current] += "\n" + line
    return result


def enum_values(text):
    values = {}
    for match in re.finditer(r"public enum class (\w+)\([^)]*\) \{(.*?)^\}", text, re.M | re.S):
        enum, body = match.groups()
        for entry in re.finditer(r"^    (\w+)\((-?[\d.]+f?)\),", body, re.M):
            values[enum + "." + entry.group(1)] = number(entry.group(2))
    return values


def triple_quoted(text, declaration):
    match = re.search(declaration + r' = (?:CommonAgsl \+ )?"""(.*?)"""', text, re.S)
    if not match:
        raise ValueError("Missing AGSL " + declaration)
    return match.group(1)


def parse_sizing(expression, enums):
    sizing = dict(SIZING)
    args = expression[expression.index("(") + 1:expression.rindex(")")]
    for name, value in re.findall(r"(\w+) = ([^,]+)", args):
        uniform = "u_" + name
        if uniform not in sizing:
            raise ValueError("Unexpected sizing argument " + name)
        sizing[uniform] = enums.get(value, number(value) if value not in enums else None)
    return sizing


def main(upstream, output):
    package = upstream / ROOT
    catalog_text = (package / "ShaderCatalog.kt").read_text()
    bases = re.findall(r"^        (\w+)Shader\.catalogEntry,", catalog_text, re.M)
    if len(bases) != 29:
        raise ValueError("Expected 29 shaders, found " + str(len(bases)))

    common_path = package / "AgslCommon.kt"
    common = triple_quoted(common_path.read_text(), "internal const val CommonAgsl")
    rows = []
    hashes = []
    imports = []
    for base in bases:
        parameter_path = package / (base + ".kt")
        source_path = package / "shaders" / (base + "Agsl.kt")
        text = parameter_path.read_text()
        params = params_block(text, base)
        enums = enum_values(text)
        shader_id = re.search(r'override val name: String = "([^"]+)"', text).group(1)
        animated = "override val isAnimated: Boolean = false" not in text
        image = "override val usesImageTexture: Boolean = true" in text
        noise = "override val usesNoiseTexture: Boolean = true" in text
        speed = number(params["speed"])
        uniforms = parse_sizing(params["sizing"], enums)
        capacities = {}
        constants = dict(re.findall(r"private const val (\w+) = (\d+)", text))
        constants["PaperShaderDefaults.ColorCapacity"] = "10"

        apply = re.search(r"override fun apply\(.*?\n    \}", text, re.S).group(0)
        for match in re.finditer(r'shader\.setColorUniform\("(\w+)", params\.(\w+)\)', apply):
            uniform, param = match.groups()
            color = params[param].strip().strip('",')
            if not re.fullmatch(r"#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?", color):
                raise ValueError("Bad color " + color)
            uniforms[uniform] = color
            capacities[uniform] = 1
        for match in re.finditer(
                r'shader\.setColorArrayUniform\("(\w+)", colors, capacity = ([\w.]+)\)', apply):
            uniform, capacity_name = match.groups()
            capacity = int(constants[capacity_name])
            colors = re.findall(r'"(#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?)"', params["colors"])
            if not colors or len(colors) > capacity:
                raise ValueError("Bad color array for " + shader_id)
            uniforms[uniform] = ",".join(colors)
            capacities[uniform] = capacity
        for match in re.finditer(r'shader\.setFloatUniform\("(\w+)", ([^\n]+)\)', apply):
            uniform, expression = match.groups()
            expression = expression.strip()
            if expression == "colors.size.toFloat()":
                value = float(len(uniforms["u_colors"].split(",")))
            else:
                boolean = re.fullmatch(r"if \(params\.(\w+)\) 1f else 0f", expression)
                enum = re.fullmatch(r"params\.(\w+)\.uniformValue", expression)
                direct = re.fullmatch(r"params\.(\w+)", expression)
                if boolean:
                    value = number(params[boolean.group(1)])
                elif enum:
                    param = enum.group(1)
                    value = enums[params[param].strip().rstrip(",")]
                elif direct:
                    value = number(params[direct.group(1)])
                else:
                    raise ValueError("Unknown uniform expression " + expression)
            uniforms[uniform] = number_text(value)

        math = triple_quoted(source_path.read_text(), "internal val " + base + "Agsl: String")
        if math.count("main(") != 1:
            raise ValueError("Unexpected main count for " + shader_id)
        math = math.replace("main(", "paperMain(")
        final_source = common + math + WRAPPER
        (output / (shader_id + ".agsl")).write_text(final_source)
        declarations = set()
        for declaration in re.findall(r"uniform (?:float|vec[234]) ([^;]+);", common + math):
            for name in declaration.split(","):
                declarations.add(re.sub(r"\[.*", "", name.strip()))
        missing = declarations - set(uniforms) - DYNAMIC
        extra = set(uniforms) - declarations
        if missing or extra:
            raise ValueError("Uniform mismatch %s missing=%s extra=%s" % (shader_id, missing, extra))
        noise_declaration = re.search(r"uniform shader [^;]*\bu_noiseTexture\b", math) is not None
        image_declaration = re.search(r"uniform shader [^;]*\bu_image\b", math) is not None
        if noise != noise_declaration:
            raise ValueError("Noise flag mismatch for " + shader_id)
        if image != image_declaration:
            raise ValueError("Image flag mismatch for " + shader_id)

        capacity_text = ",".join(name + "=" + str(capacities[name]) for name in sorted(capacities))
        uniform_text = ";".join(name + "=" + str(uniforms[name]) for name in sorted(uniforms))
        rows.append("\t".join((shader_id, str(animated).lower(), str(image).lower(),
                               str(noise).lower(), number_text(speed), capacity_text, uniform_text)))
        hashes.append("\t".join((shader_id, sha(source_path.read_bytes()), sha(math.encode()),
                                 sha(final_source.encode()))))
        imports.append({
            "id": shader_id,
            "parameters": str(parameter_path.relative_to(upstream)),
            "parameters_sha256": sha(parameter_path.read_bytes()),
            "source": str(source_path.relative_to(upstream)),
            "source_sha256": sha(source_path.read_bytes()),
            "imported_math_sha256_after_entry_point_rename": sha(math.encode()),
            "output": shader_id + ".agsl",
            "output_sha256": sha(final_source.encode()),
        })

    noise_path = package.parent / "res/drawable-nodpi/ps_noise.png"
    # The actual resource directory is src/main/res, not src/main/kotlin/res.
    noise_path = upstream / "paper-shaders-compose/src/main/res/drawable-nodpi/ps_noise.png"
    shutil.copyfile(noise_path, output / "ps_noise.png")
    (output / "catalog.tsv").write_text("\n".join(rows) + "\n")
    (output / "source-hashes.tsv").write_text("\n".join(hashes) + "\n")
    provenance = {
        "component": "Paper Shaders AGSL catalog and adapted Mesh Gradient",
        "license": "Apache-2.0",
        "imported": datetime.date.today().isoformat(),
        "android_port": {
            "repository": "https://github.com/AndreFrelicot/paper-shaders-android",
            "revision": REVISION,
            "tag": "0.0.3",
            "copyright": "Copyright 2026 Andre Frelicot",
        },
        "upstream": {
            "repository": "https://github.com/paper-design/shaders",
            "copyright": "Copyright Lost Coast Labs, Inc.",
            "notice": "Shader algorithms, presets, and visual design are based on Paper Shaders.",
        },
        "catalog_import": {
            "generator": "app/src/test/tools/import_paper_shaders.py",
            "common_source": str(common_path.relative_to(upstream)),
            "common_source_sha256": sha(common_path.read_bytes()),
            "common_agsl_sha256": sha(common.encode()),
            "sources": imports,
            "noise": {
                "source": str(noise_path.relative_to(upstream)),
                "source_sha256": sha(noise_path.read_bytes()),
                "output": "ps_noise.png",
                "output_sha256": sha((output / "ps_noise.png").read_bytes()),
            },
            "changes": [
                "Renamed each upstream main entry point to paperMain without changing its math.",
                "Added a shared wrapper that safely unpremultiplies, applies the existing ambient grade, and premultiplies.",
                "Extracted upstream default parameters, sizing, enum values, speed, and texture flags into catalog.tsv.",
            ],
        },
        "adapted_mesh": {
            "local_file": "app/src/main/java/com/eza/spicyex/ambient/MeshGradientShader.java",
            "upstream_revision": "71be01576e8402c62ac76386bb4a7c59092e24c7",
            "upstream_version": "0.0.82",
            "changes": [
                "Restored u_grainMixer and u_grainOverlay guards.",
                "Specialised ps_objectUV for the existing ambient sizing contract.",
                "Kept the adapted palette and transition runtime path unchanged.",
            ],
        },
    }
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))
