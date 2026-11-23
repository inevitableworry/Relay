"""Check actual file table of an ExeFS PFS0 dev NSP (not installability)."""
from pathlib import Path
import hashlib
import struct
import sys

folder = Path(__file__).resolve().parent
data = (folder / "relay-switch1-exefs-dev.nsp").read_bytes()
errors=[]
if data[:4]!=b"PFS0":
    errors.append("NSP is not a PFS0 container")
else:
    count, names_size, _ = struct.unpack_from("<III", data, 4)
    if count!=2 or names_size>4096:
        errors.append("Unexpected ExeFS file count or string table")
    else:
        entry_base=16
        strings_base=entry_base+24*count
        payload_base=strings_base+names_size
        names=[]
        for i in range(count):
            offset,length,name_index,_=struct.unpack_from("<QQII",data,entry_base+i*24)
            if name_index>=names_size or payload_base+offset+length>len(data):
                errors.append("Invalid ExeFS PFS0 file record")
                continue
            begin=strings_base+name_index
            end=data.find(b"\0",begin,strings_base+names_size)
            if end<begin:
                errors.append("Invalid file name")
                continue
            name=data[begin:end].decode("ascii","replace")
            names.append(name)
            content=data[payload_base+offset:payload_base+offset+length]
            if content!=(folder/"exefs-dev"/name).read_bytes():
                errors.append("Payload mismatch: "+name)
        if sorted(names)!=["main","main.npdm"]:
            errors.append("Expected main NSO and main.npdm only")
print("Development PFS0 structure:", "PASS" if not errors else "FAIL")
print("Size:",len(data),"bytes")
print("SHA-256:",hashlib.sha256(data).hexdigest())
print("This is an ExeFS-only development package, NOT a signed installable game NSP.")
for error in errors: print("ERROR:",error)
sys.exit(bool(errors))
