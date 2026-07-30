# Third-party notices

TCG Locked includes material from the projects below. Their licence terms are
reproduced in full as those licences require.

---

## Bronzeman TCG

`src/main/resources/com/tcglocked/resource-nodes.json` is derived from
`src/main/resources/resource_nodes.json` in Bronzeman TCG
(https://github.com/Felmeme/bronzeman-tcg). The interaction schema it encodes
(`kind`, `options`, `requiredCardGroups`, `groupRoles`, `groupLabels`,
`requireAll`) is Felmeme's design, and `TcgInteractionCatalog` reads it
unchanged so the two plugins stay compatible.

Only attribution keys (`_source`, `_copyright`, `_license`) were added at the
top of the file. No entries were altered.

```
BSD 2-Clause License

Copyright (c) 2026, Felmeme
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```
