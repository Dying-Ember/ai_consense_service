"""Resolve repository layout or an explicitly configured isolated/package root."""
from pathlib import Path


def resolve_workspace(here, environ):
    directory = Path(here).resolve()
    if 'CONSENSE_WORKSPACE_ROOT' in environ:
        value = environ['CONSENSE_WORKSPACE_ROOT']
        if not isinstance(value, str) or not value.strip():
            raise ValueError('CONSENSE_WORKSPACE_ROOT must name an existing directory')
        explicit = Path(value).expanduser().resolve()
        if not explicit.is_dir():
            raise ValueError('CONSENSE_WORKSPACE_ROOT must name an existing directory')
        return explicit
    # Structural repository detection works for arbitrary service directory
    # names. It does not inspect document filenames or assume stage depth.
    if (directory.name == 'vetting_eval' and directory.parent.name == 'tools'
            and (directory.parents[1] / 'pom.xml').is_file()):
        return directory.parents[2]
    raise ValueError('Set CONSENSE_WORKSPACE_ROOT explicitly for an isolated or portable package')
