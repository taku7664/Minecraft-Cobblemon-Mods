"""Read every ability/item workbook effect and inventory its source/test references.

This is a source inventory, not a behavioral coverage certificate. An ID mention does not
prove its hook runs in scoring; no ID mention does not disprove declarative processing.
Run with --out on F: for scratch output. The original workbook is never modified.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

NS = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
RID = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"


def workbook_rows(path: Path):
    with zipfile.ZipFile(path) as archive:
        strings = ["".join(node.itertext()) for node in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        relations = {node.attrib["Id"]: node.attrib["Target"] for node in
                      ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))}
        sheets = ET.fromstring(archive.read("xl/workbook.xml")).find("s:sheets", NS)
        for sheet in sheets:
            name = sheet.attrib["name"]
            if name not in {"특성", "도구"}:
                continue
            target = relations[sheet.attrib[RID]]
            xml = ET.fromstring(archive.read(target.lstrip("/") if target.startswith("/") else "xl/" + target))
            headers = {}
            for row in xml.findall(".//s:sheetData/s:row", NS):
                values = {}
                for cell in row.findall("s:c", NS):
                    column = re.match(r"[A-Z]+", cell.attrib["r"])[0]
                    value = cell.find("s:v", NS)
                    if cell.attrib.get("t") == "inlineStr":
                        values[column] = "".join(cell.find("s:is", NS).itertext())
                    elif value is not None:
                        values[column] = strings[int(value.text)] if cell.attrib.get("t") == "s" else value.text
                if int(row.attrib["r"]) == 1:
                    headers = values
                else:
                    yield name, int(row.attrib["r"]), {headers[col]: value for col, value in values.items()}


def source_group(path: str):
    if path.startswith("src/test/"):
        return "엔진 테스트 참조" if "/betterai/engine/" in path else "로컬 테스트 참조"
    if "/betterai/engine/" in path:
        return "포팅 엔진 참조"
    if "/betterai/simulation/" in path or "/betterai/search/" in path:
        return "실행·탐색 참조"
    if "/betterai/outcome/" in path:
        return "턴 투영기 참조"
    return "로컬 계산·상태 참조"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--module", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    module = args.module.resolve()
    workbook = module / "docs/betterai/engine/AI_엔진_구현_현황.xlsx"
    entries = list(workbook_rows(workbook))
    ids = {entry[2]["ID"] for entry in entries}
    references = {ident: {} for ident in ids}
    for file in sorted((module / "src").rglob("*.kt")):
        relative = file.relative_to(module).as_posix()
        if "/betterai/" not in relative and "/internal/ai/" not in relative:
            continue
        for number, line in enumerate(file.read_text(encoding="utf-8").splitlines(), 1):
            for ident in set(re.findall(r'"([a-z0-9]+)"', line)) & ids:
                references[ident].setdefault(source_group(relative), []).append(f"{relative}:{number}")
    columns = ["분류", "원본 행", "ID", "이름", "세부 효과", "훅 이름", "기존 엔진 상태", "기존 엔진 테스트",
               "이번 목록 검수", "로컬 계산·상태 참조", "턴 투영기 참조", "실행·탐색 참조", "포팅 엔진 참조",
               "로컬 테스트 참조", "엔진 테스트 참조"]
    output = []
    for category, number, entry in entries:
        ident = entry["ID"]
        row = dict(zip(columns[:8], [category, number, ident, entry.get("한국어 이름", ""), entry.get("세부 효과", ""),
                                    entry.get("훅 이름", ""), entry.get("상태", ""), entry.get("검증 테스트", "")]))
        row["이번 목록 검수"] = "직접 참조 확인: 효과별 실행 증거는 별도" if references[ident].get("로컬 계산·상태 참조") else \
            "직접 참조 없음: 선언형 경로 및 효과별 실행 증거는 별도"
        for group in columns[9:]:
            row[group] = "; ".join(references[ident].get(group, []))
        output.append(row)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(output)
    print(json.dumps({"workbookSha256": hashlib.sha256(workbook.read_bytes()).hexdigest(),
                      "counts": {category: {"entities": len({r["ID"] for r in output if r["분류"] == category}),
                                             "effects": sum(r["분류"] == category for r in output)}
                                 for category in ["특성", "도구"]}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
