import React, {useState} from "react";
import {IconButton, Input, Table, TableBody, TableCell, TableHead, TableRow} from "@material-ui/core";
import {makeStyles} from "@material-ui/core/styles";
import DeleteIcon from "@material-ui/icons/Delete";

const useStyles = makeStyles(theme => ({
  actionsColumn: {
    width: '200px',
    padding: '4px',
    paddingRight: '40px',
    textAlign: 'right'
  }
}));

export interface EditColumnParams {
  name: string,
  headerName: string,
  className: string,
  editable?: boolean,
  validate?: (value: string) => boolean
}

interface EditTableRowParams {
  columns: Array<EditColumnParams>,
  rowNum: number,
  values: any,
  onChange: (column: string, value: string) => void,
  onRemove: () => void
}

const EditTableRow = (params: EditTableRowParams) => {
  const { columns, rowNum, values, onChange, onRemove } = params

  const [editColumn, setEditColumn] = useState(-1)
  const [editValue, setEditValue] = useState('')

  const classes = useStyles()

  const valuesColumns = columns.map((column, index) =>
    (<TableCell key={index} className={column.className}
                onClick={() => {
                  if (column.editable && editColumn != index) {
                    setEditColumn(index)
                    setEditValue(values[column.name])
                  }
                }}
                onBlur={() => {
                  onChange?.(column.name, editValue)
                  setEditColumn(-1)
                }}
    >
      {editColumn === index ? (
        <Input
          value={editValue}
          autoFocus={true}
          onChange={e => {
            setEditValue(e.target.value)
          }}
        />
      ) : (
        values[column.name]
      )}
    </TableCell>))
  return (<TableRow>
      {[...valuesColumns, (<TableCell key={valuesColumns.length} className={classes.actionsColumn}>
             <IconButton
               onClick={() => onRemove()}
               title="Delete"
             >
                <DeleteIcon/>
             </IconButton>
           </TableCell>
         )]}
    </TableRow>)
}

interface EditTableParams {
  className: string,
  columns: Array<EditColumnParams>,
  rows: Array<any>,
  onRowChange: (row: number, column: string, value: string) => void,
  onRowRemove: (row: number) => void
}

export const EditTable = (props: EditTableParams) => {
  const { className, columns, rows, onRowChange, onRowRemove } = props
  const classes = useStyles()

  return (
    <Table
      className={className}
      stickyHeader
    >
      <TableHead>
        <TableRow>
          { columns.map((column, index) =>
            <TableCell key={index} className={column.className}>{column.headerName}</TableCell>) }
          <TableCell key={columns.length} className={classes.actionsColumn}>Actions</TableCell>
        </TableRow>
      </TableHead>
      { <TableBody>
          { rows.map((row, index) => {
            return (<EditTableRow key={index} columns={columns} rowNum={index} values={row}
                           onChange={(column, value) => onRowChange(index, column, value) }
                           onRemove={() => onRowRemove(index) }/>) })}
        </TableBody> }
    </Table>)
}

export default EditTable;