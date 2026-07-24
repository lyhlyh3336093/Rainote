export interface InputProps {
    value?: string;
    intermediate?: boolean;
    type: string | number;
    onChange?: (...agrs: any) => void;
    forwardRef?: (el: any) => void;
    props?: {
        property?: {
            select: string
        };
        placeholder?: string;

    };
    emit: (data: any) => void;
}

