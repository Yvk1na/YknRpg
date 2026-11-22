"""Stage the MythicMobs model bindings and CraftEngine pack metadata."""
import argparse
import copy
import json
import re
import shutil
import zipfile
from collections import defaultdict
from pathlib import Path

import yaml
from PIL import Image


def patch_models(source, models):
    """Add ItemModel while preserving the other item fields and YAML comments."""
    parsed = yaml.safe_load(source)
    expected = copy.deepcopy(parsed)
    blocks = re.split(r"(?m)^(YK[FM]_[A-Za-z0-9_]+):[ \t]*\n", source)
    for i in range(1, len(blocks), 2):
        item_id, body = blocks[i:i + 2]
        if item_id not in models:
            continue
        key = models[item_id]
        options = expected[item_id].setdefault("Options", {})
        current_model = options.get("ItemModel")
        if current_model is not None and current_model != key:
            raise ValueError(f"{item_id} already points to {current_model}")
        if current_model == key:
            continue
        options["ItemModel"] = key
        if re.search(r"(?m)^  Options: \{.*\}$", body):
            body = re.sub(r"(?m)^  Options: \{(.*)\}$",
                          lambda match: f'  Options: {{{match[1] + ", " if match[1] else ""}ItemModel: "{key}"}}',
                          body, count=1)
        elif re.search(r"(?m)^  Options:[ \t]*$", body):
            body = re.sub(r"(?m)^  Options:[ \t]*$", f'  Options:\n    ItemModel: "{key}"', body, count=1)
        else:
            body = re.sub(r"(?m)^(  Id: .+)$",
                          lambda match: match[0] + f'\n  Options:\n    ItemModel: "{key}"', body, count=1)
        blocks[i + 1] = body
    staged = blocks[0] + "".join(f"{blocks[i]}:\n{blocks[i + 1]}" for i in range(1, len(blocks), 2))
    parsed_staged = yaml.safe_load(staged)
    if (parsed_staged != expected or not models.keys() <= expected.keys()
            or any(parsed_staged[item_id].get("Options", {}).get("ItemModel") != model
                   for item_id, model in models.items())):
        raise ValueError("Unexpected MythicMobs changes after adding item models")
    return staged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--require-complete", action="store_true")
    parser.add_argument("--package", action="store_true")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    design = project / "docs/design/fish-mining"
    stage = project / "build/fish-mining-art"
    roots = {"yknfish": Path("C:/Users/y/IdeaProjects/YknFish"), "yknmining": Path("D:/codex projects/YknMining")}
    items = json.loads((design / "items.json").read_text(encoding="utf-8"))
    files = defaultdict(dict)
    exported = set()
    for item in items:
        ns, slug = item["model"].split(":")
        pack = stage / ns / "plugins/CraftEngine/resources" / ns
        model = pack / f"resourcepack/assets/{ns}/items/{slug}.json"
        icon = pack / f"resourcepack/assets/{ns}/textures/item/{slug}.png"
        if not model.is_file() or not icon.is_file():
            if args.require_complete:
                raise FileNotFoundError(item["id"])
            continue
        asset_root = pack / f"resourcepack/assets/{ns}"
        with Image.open(icon) as image:
            if image.size != (32, 32) or image.convert("RGBA").getchannel("A").getextrema() != (0, 255):
                raise ValueError(f"Expected a transparent 32x32 icon: {item['id']}")
        definition = json.loads(model.read_text(encoding="utf-8"))["model"]
        held = item["kind"] in {"fishing_rod", "pickaxe"}
        menu_model = definition["fallback"] if held else definition
        if menu_model != {"type": "minecraft:model", "model": f"{ns}:item/{slug}"}:
            raise ValueError(f"Invalid inventory model for {item['id']}")
        if held:
            with Image.open(asset_root / f"textures/item/{slug}_in_hand.png") as image:
                if image.size != (64, 64) or image.convert("RGBA").getchannel("A").getextrema() != (0, 255):
                    raise ValueError(f"Expected a transparent 64x64 held texture: {item['id']}")
            expected_contexts = {p + "_" + h + "hand" for p in ("firstperson", "thirdperson") for h in ("left", "right")}
            if definition["property"] != "minecraft:display_context" or set(definition["cases"][0]["when"]) != expected_contexts:
                raise ValueError(f"Invalid hand display selection for {item['id']}")
            if definition["cases"][0]["model"]["model"] != f"{ns}:item/{slug}_in_hand":
                raise ValueError(f"Invalid held model for {item['id']}")
        files[(ns, item["file"])][item["id"]] = item["model"]
        exported.add(item["id"])
    for (ns, filename), models in files.items():
        relative = Path("MythicMobs/items") / filename
        source = roots[ns] / "integration" / relative
        destination = stage / ns / "plugins" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(patch_models(source.read_text(encoding="utf-8-sig"), models), encoding="utf-8")
    for ns in {namespace for namespace, _ in files}:
        pack = stage / ns / "plugins/CraftEngine/resources" / ns
        if ns == "yknmining":
            original = roots[ns] / f"integration/CraftEngine/resources/{ns}"
            for source in original.rglob("*"):
                if source.is_file():
                    destination = pack / source.relative_to(original)
                    if not destination.exists():
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(source, destination)
        else:
            metadata = {"author": "Ykn", "namespace": ns, "version": "1.0.0", "description": "渔获、鱼竿与渔装"}
            (pack / "pack.yml").write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        count = sum(len(models) for (namespace, _), models in files.items() if namespace == ns)
        expected = sum(item["plugin"] == ns for item in items)
        print(f"{ns}: {count}/{expected} item bindings")
        if args.package:
            project_name = {"yknfish": "YknFish", "yknmining": "YknMining"}[ns]
            suffix = "" if count == expected else "-Preview"
            archive = project / "build/distributions" / f"{project_name}-Pixel-Art{suffix}.zip"
            archive.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for file in sorted((stage / ns / "plugins").rglob("*")):
                    if file.is_file():
                        output.write(file, file.relative_to(stage / ns))
            print(archive)
    progress = {
        "expected": len(items),
        "exported": len(exported),
        "pending": [
            {key: item[key] for key in ("id", "name", "plugin", "kind")}
            for item in items if item["id"] not in exported
        ],
    }
    (design / "progress.json").write_text(json.dumps(progress, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
