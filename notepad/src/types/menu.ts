export interface Menu {
    id?: string | number;
    children?: Menu[];
    title:string;
    noteType:string|number;
}

export interface  MenuProps{
    data:string
}