import csv
import json
import re
import zipfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta

SOURCE = r"C:\Users\dubey\Downloads\Maps Record.xlsx"
TARGET = r"C:\Users\dubey\Downloads\New.csv"
OUTPUT = "New_import_ready.csv"
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


def col_number(cell_ref):
    letters = re.match(r"[A-Z]+", cell_ref).group(0)
    value = 0
    for letter in letters:
        value = value * 26 + ord(letter) - 64
    return value


def excel_date(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    if not 20_000 <= number <= 60_000:
        return None
    return (datetime(1899, 12, 30) + timedelta(days=number)).strftime("%d/%m/%Y")


def map_parts(value):
    if not isinstance(value, str):
        return None
    match = re.match(r"^\s*([A-Za-z]*\d+[A-Za-z]?)\s*-\s*(.+?)\s*$", value)
    if not match:
        return None
    return match.group(1), match.group(2)


def split_person_and_notes(value):
    """Keep a person's name separate from assignment details in the source sheet."""
    original = value.strip()
    parenthetical = re.findall(r"\(([^)]*)\)", original)
    without_parenthetical = re.sub(r"\([^)]*\)", "", original).strip()
    name_match = re.search(
        r"\b(?:[A-Z]\.?|[A-Z][a-z]+)\s+([A-Z][A-Za-z'’-]+)\b",
        without_parenthetical,
    )
    assigned_to = ""
    remainder = without_parenthetical
    if name_match:
        candidate = name_match.group(0)
        first_word, last_word = candidate.replace(".", "").split(maxsplit=1)
        non_names = {"first", "second", "week", "co", "letters", "group", "sat", "saturday"}
        if first_word.casefold() not in non_names and last_word.casefold() not in non_names:
            assigned_to = candidate
            remainder = without_parenthetical[:name_match.start()] + without_parenthetical[name_match.end():]

    notes = []
    clean_remainder = re.sub(r"^[\s,;:/-]+|[\s,;:/-]+$", "", remainder)
    if clean_remainder:
        notes.append(clean_remainder)
    notes.extend(part.strip() for part in parenthetical if part.strip())
    return assigned_to, "; ".join(notes)


with zipfile.ZipFile(SOURCE) as archive:
    strings_root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    strings = ["".join(node.text or "" for node in item.iter(NS + "t")) for item in strings_root]
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    sheet_names = [item.attrib["name"] for item in workbook.find(NS + "sheets")]

    records = []
    seen = set()
    for sheet_index, sheet_name in enumerate(sheet_names, 1):
        root = ET.fromstring(archive.read(f"xl/worksheets/sheet{sheet_index}.xml"))
        cells = {}
        for cell in root.iter(NS + "c"):
            raw = cell.findtext(NS + "v")
            if raw is None:
                continue
            value = strings[int(raw)] if cell.attrib.get("t") == "s" else raw
            reference = cell.attrib["r"]
            row = int(re.search(r"\d+", reference).group(0))
            cells[(row, col_number(reference))] = value

        header_rows = []
        for row in sorted({key[0] for key in cells}):
            header = {col: map_parts(value) for (cell_row, col), value in cells.items() if cell_row == row}
            header = {col: value for col, value in header.items() if value}
            # A map-record block has several map titles across the row.
            if len(header) >= 2:
                header_rows.append((row, header))

        for index, (header_row, header) in enumerate(header_rows):
            end_row = header_rows[index + 1][0] if index + 1 < len(header_rows) else max(row for row, _ in cells) + 1
            # Each following pair of rows is person then assignment date for the same map columns.
            for row in range(header_row + 1, end_row - 1, 2):
                for col, (map_no, title) in header.items():
                    person = cells.get((row, col), "")
                    assigned_on = excel_date(cells.get((row + 1, col), ""))
                    completed_on = excel_date(cells.get((row + 1, col + 1), ""))
                    if not isinstance(person, str) or not person.strip() or not assigned_on:
                        continue
                    assigned_to, notes = split_person_and_notes(person)
                    record = (map_no, title, assigned_to, assigned_on, completed_on or "", notes)
                    if record not in seen:
                        seen.add(record)
                        records.append(record)


def date_key(value):
    return datetime.strptime(value, "%d/%m/%Y")


def map_key(value):
    match = re.match(r"^([A-Za-z]*)(\d+)([A-Za-z]*)$", value)
    if not match:
        return (2, value.casefold(), 0, "")
    prefix, number, suffix = match.groups()
    return (0 if not prefix else 1, prefix.casefold(), int(number), suffix.casefold())


# The Ledger reads the latest history record as the current assignment, so retain
# a predictable oldest-to-newest sequence for every card.
records.sort(key=lambda item: (map_key(item[0]), date_key(item[3]), item[2].casefold(), item[5].casefold()))

with open(TARGET, newline="", encoding="utf-8-sig") as source_file:
    existing_rows = list(csv.reader(source_file))

# Replace an existing Ledger section, rather than duplicating it.
ledger_start = next(
    (index for index, row in enumerate(existing_rows)
     if row and row[0].strip() in {"Ledger DNC Card", "Ledger map number"}),
    len(existing_rows),
)
card_rows = [row for row in existing_rows[:ledger_start] if any(cell.strip() for cell in row)]

with open(OUTPUT, "w", newline="", encoding="utf-8") as output_file:
    writer = csv.writer(output_file)
    writer.writerows(card_rows)
    writer.writerow([])
    writer.writerow(["Ledger DNC Card", "Assigned to", "Assigned on", "Completed on", "Notes"])
    previous_map = None
    for map_no, title, assigned_to, assigned_on, completed_on, notes in records:
        if previous_map is not None and map_no != previous_map:
            writer.writerow([])
        writer.writerow([f"{map_no} - {title}" if map_no != previous_map else "", assigned_to, assigned_on, completed_on, notes])
        previous_map = map_no

print(json.dumps({"ledgerRecords": len(records), "output": OUTPUT}, ensure_ascii=False))
